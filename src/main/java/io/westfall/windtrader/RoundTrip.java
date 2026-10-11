// SPDX-License-Identifier: EPL-2.0
package io.westfall.windtrader;

import com.google.inject.Injector;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.parser.IParser;
import org.eclipse.xtext.parser.ParseException;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.xtext.SysMLStandaloneSetup;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Parse-only validator for SysML v2 text.
 *
 * <p>Before parsing, we bootstrap {@link SysMLInteractive#getInstance()} so the pilot's
 * full EMF/EPackage feature-wiring is registered. Without it, a bare
 * {@link SysMLStandaloneSetup} parse throws
 * {@code Unresolved proxy ... EPackage has not been registered} on valid input and NPEs
 * on unit expressions in feature values (e.g. {@code attribute f = 10.0 [N]}) with
 * {@code InvocationExpressionImpl.getOperand} -&gt; "settings" is null.
 * See Westfall-io/windtrader-java#4.
 */
public class RoundTrip {

    private static final int EXIT_OK = 0;
    private static final int EXIT_INVALID = 2;
    private static final int EXIT_RUNTIME = 3;

    private static String readAllStdin() throws Exception {
        return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void usage() {
        System.err.println("windtrader-java usage:");
        System.err.println("  check      : parse-only validate stdin, exit 0 if valid, 2 if invalid");
        System.err.println("  echo       : parse-only validate stdin then print parsed text if valid");
        System.err.println("  export     : parse stdin and print the SysMLv2 element JSON graph (API shape)");
        System.err.println("  batch-export : read .sysml paths from stdin, write <path>.export.json for each (single JVM)");
        System.err.println("  versions   : print version info");
    }

    /**
     * Set up the parser. Bootstrap the pilot's interactive machinery first so EPackages
     * and feature delegates are fully registered, then create the Xtext injector.
     */
    private static Injector setupInjector() {
        // Critical: full EMF/EPackage registration. The bare StandaloneSetupGenerated
        // leaves EPackages unregistered (invalid input) and feature `settings` delegates
        // null (NPE on unit expressions). SysMLInteractive.getInstance() performs the
        // complete wiring the grammar needs (via createInstance()).
        SysMLInteractive.getInstance();
        // Use the same hand-written setup class the pilot does — the ...Generated
        // variant is exactly the distinction that caused this bug on the KerML side.
        return new SysMLStandaloneSetup().createInjectorAndDoEMFRegistration();
    }

    private static void printSyntaxErrors(IParseResult pr) {
        if (pr == null) return;

        Iterable<INode> errs = pr.getSyntaxErrors();
        if (errs == null) return;

        for (INode n : errs) {
            int line = n.getStartLine();     // available on INode
            int offset = n.getOffset();      // absolute offset
            String text = n.getText();
            if (text != null) text = text.replace("\n", "\\n");
            System.err.println("error: line=" + line + " offset=" + offset + " near=" + text);
        }
    }

    private static String runJupyterExport(String text) {
        return runJupyterExport(text, null);
    }

    /**
     * Run the pilot's Jupyter-faithful export ({@code SysMLInteractive.export}) over
     * {@code text}, returning the API-shaped element JSON string.
     *
     * <p>The pilot's Jupyter {@code %export} magic resolves a named element and calls
     * {@code JsonElementProcessingFacade.getTraversal().visit(element)} +
     * {@code toJsonTree(true)} on it (see
     * {@code org.omg.sysml.jupyter.kernel.magic.Export} / {@code SysMLInteractive.export}).
     * This reproduces that path exactly: {@code process(text)} to load the text into
     * the interactive's resource set, derive the root element's export name, then call
     * the public {@code export(name, [])}. It is deliberately NOT the
     * {@code SysML2JSON} CLI (which runs transformAll over all roots and NPEs on
     * control-flow/cross-resource models — a CLI-only defect the Jupyter path does
     * not have). See Westfall-io/windtrader-java#11.
     *
     * <p>The Jupyter kernel accumulates parsed cells in its resource set, so a cell that
     * {@code import PictureTaking::*} resolves {@code PictureTaking} because an earlier cell
     * loaded it. When {@code sourcePath} is provided we reproduce that accumulated model:
     * before processing the target text we {@code process()} its same-directory sibling
     * {@code .sysml} files that it imports, in dependency (topological) order. Without this,
     * a single file split across sibling files (Camera→PictureTaking, the Arrowhead library
     * chain) fails with "Couldn't resolve reference to Namespace 'X'" exactly as it would in
     * a Jupyter cell with no earlier cell defining X.
     *
     * <p>The output is the expanded element graph (all EStructuralFeatures, with
     * {@code @id} links to referenced elements), deterministic in shape (count +
     * type multiset) though {@code @id}s are fresh UUIDs per run.
     *
     * @return the JSON string, or {@code null} on failure (library unavailable / error)
     */
    private static String runJupyterExport(String text, String sourcePath) {
        java.io.PrintStream realOut = System.out;
        // SysMLInteractive.process / export / loadLibrary print "Reading..." chatter;
        // discard stdout so the export stays pure JSON.
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            String libDir = ensureLibraryDir();
            if (libDir == null) {
                return null;
            }
            SysMLInteractive interactive = SysMLInteractive.getInstance();
            interactive.loadLibrary(libDir);
            // Reproduce Jupyter's accumulated resource set: preload the target's
            // same-directory sibling imports in dependency order before the target.
            if (sourcePath != null) {
                preloadSiblingImports(interactive, sourcePath);
            }
            org.omg.sysml.interactive.SysMLInteractiveResult result = interactive.process(text);
            // Note: we deliberately do NOT gate on result.hasErrors(). The pilot's
            // Jupyter %export resolves the named element and exports regardless of
            // validation issues (SysMLInteractive.export only fails if resolve(name)
            // returns null). Matching that, we proceed to export the root even if the
            // model has semantic errors — otherwise models with warnings/errors that
            // Jupyter still exports would fail here. A null root/result is a real failure.
            if (result == null || result.getRootElement() == null) {
                System.err.println("error: msg=export: input produced no root element; cannot export.");
                return null;
            }
            Element root = result.getRootElement();
            String name = (root == null) ? null : root.getQualifiedName();
            if ((name == null || name.isBlank()) && root instanceof org.omg.sysml.lang.sysml.Namespace) {
                // Bare part def / usage: root namespace is unnamed; use first named
                // owned member (e.g. the member element of a bare `part def P;`).
                for (Element m : ((org.omg.sysml.lang.sysml.Namespace) root).getOwnedMember()) {
                    String mn = m.getName();
                    if (mn != null && !mn.isBlank()) { name = mn; break; }
                }
            }
            if (name == null || name.isBlank()) {
                System.err.println("error: msg=export: cannot determine an exportable root element name.");
                return null;
            }
            Object exported = interactive.export(name, java.util.Collections.emptyList());
            if (exported == null) {
                System.err.println("error: msg=export: pilot export returned null.");
                return null;
            }
            String json = String.valueOf(exported);
            // The pilot's export() returns "ERROR:Couldn't resolve reference to
            // Element 'X'" as a plain string when resolve(name) fails (e.g. bare
            // top-level definitions the Jupyter %export magic also cannot resolve).
            // Treat that as a loud failure — never emit an error string as export JSON.
            if (json.startsWith("ERROR:")) {
                System.err.println("error: msg=export: pilot could not resolve root element '" + name + "': " + json.trim());
                return null;
            }
            return json;
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            return null;
        } finally {
            System.setOut(realOut);
        }
    }

    /**
     * Preload a model's same-directory sibling {@code .sysml} imports into the
     * interactive's resource set, in dependency (topological) order, mirroring how
     * Jupyter accumulates parsed cells so a later cell's {@code import Sibling::*}
     * resolves.
     *
     * <p>Only siblings physically present next to {@code sourcePath} that the target
     * (transitively) imports are processed; standard-library namespaces (ScalarValues,
     * SI, Time, ...) are already provided by {@code loadLibrary} and never re-processed
     * here. We look for {@code import Ns::*} / {@code import Ns::**} statements in each
     * file and map {@code Ns} to an adjacent {@code Ns.sysml}.
     */
    private static void preloadSiblingImports(SysMLInteractive interactive, String sourcePath) {
        try {
            java.util.Set<String> visited = new java.util.HashSet<>();
            java.util.List<String> order = new java.util.ArrayList<>();
            resolveImports(sourcePath, visited, order);
            for (String sibling : order) {
                if (!sibling.equals(sourcePath)) {
                    String txt = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(sibling)),
                            java.nio.charset.StandardCharsets.UTF_8);
                    interactive.process(txt);
                }
            }
        } catch (Throwable t) {
            // Preload is best-effort: a missing/unreadable sibling must not abort the
            // export (the pilot's export() will surface the real resolution error if any).
        }
    }

    /**
     * Depth-first resolution of the transitive same-directory sibling imports of
     * {@code path}, appending files to {@code order} in dependency order (dependencies
     * before dependents), skipping {@code visited}.
     */
    private static void resolveImports(String path, java.util.Set<String> visited, java.util.List<String> order) {
        if (!visited.add(path)) {
            return;
        }
        try {
            String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Path dir = java.nio.file.Paths.get(path).toAbsolutePath().getParent();
            java.nio.file.Path self = java.nio.file.Paths.get(path).toAbsolutePath();
            for (String ns : importedNamespaces(text)) {
                java.nio.file.Path cand = dir.resolve(ns + ".sysml");
                if (java.nio.file.Files.exists(cand)) {
                    java.nio.file.Path abs = cand.toAbsolutePath();
                    if (!abs.equals(self)) {
                        resolveImports(abs.toString(), visited, order);
                        order.add(abs.toString());
                    }
                }
            }
        } catch (Throwable t) {
            // best-effort
        }
    }

    /**
     * Extract the first segment of every {@code import Ns::...} statement in {@code text}
     * (the namespace being imported), as a de-duplicated list.
     */
    private static java.util.List<String> importedNamespaces(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?im)^\\s*(?:public|private)?\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:[:*]").matcher(text);
        while (m.find()) {
            String ns = m.group(1);
            if (!out.contains(ns)) {
                out.add(ns);
            }
        }
        return out;
    }

    /**
     * Resolve the SysML standard-library directory (bundled cache or
     * WINDTRADER_SYSML_LIBRARY override).
     */
    private static String ensureLibraryDir() {
        try {
            String libDir = bundledLibraryDir();
            if (libDir == null) {
                System.err.println("error: msg=SysML standard library unavailable; cannot export.");
            }
            return libDir;
        } catch (Throwable t) {
            System.err.println("error: msg=export: library resolution failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Resolve the directory containing the SysML standard library (Kernel Libraries,
     * Systems Library, Domain Libraries). Precedence:
     *   1. WINDTRADER_SYSML_LIBRARY env var (explicit override / offline scratch).
     *   2. A previously-extracted cache under the user cache dir.
     *   3. Extract from the bundled jar resources (windtrader-lib/...) into the cache.
     * Returns null if no bundle is present and no override is set.
     */
    private static String bundledLibraryDir() {
        String override = System.getenv("WINDTRADER_SYSML_LIBRARY");
        if (override != null && !override.isBlank()) {
            // Validate the override actually points at a full library layout (Kernel
            // Libraries + Systems Library + Domain Libraries). The pilot's loadLibrary
            // is lenient on a bad path and would otherwise proceed with an empty or
            // partial library index, silently producing wrong export shape. A missing
            // or partial override must fail loudly.
            if (isFullLibraryLayout(override)) {
                return override;
            }
            System.err.println("error: WINDTRADER_SYSML_LIBRARY override does not contain the full library layout "
                    + "(Kernel Libraries, Systems Library, Domain Libraries): " + override);
            return null;
        }
        try {
            java.nio.file.Path cache = java.nio.file.Paths.get(
                    System.getProperty("user.home"), ".cache", "windtrader", "sysml-library");
            // A jar-resource bundle marker tells us whether resources exist to extract.
            boolean haveResources = RoundTrip.class.getClassLoader()
                    .getResource("windtrader-lib/marker.txt") != null;
            if (isFullLibraryLayout(cache.toString())) {
                return cache.toString();
            }
            if (haveResources && extractLibraryTo(cache)) {
                // Require the extraction to have produced the full expected layout; an
                // interrupted/partial extraction must not look valid forever.
                return isFullLibraryLayout(cache.toString()) ? cache.toString() : null;
            }
            return null;
        } catch (Exception e) {
            System.err.println("warning: bundledLibraryDir: " + e.getMessage());
            return null;
        }
    }

    /** True if {@code dir} has the three standard-library subdirectories. */
    private static boolean isFullLibraryLayout(String dir) {
        if (dir == null) return false;
        java.nio.file.Path base = java.nio.file.Paths.get(dir);
        return java.nio.file.Files.isDirectory(base.resolve("Kernel Libraries"))
                && java.nio.file.Files.isDirectory(base.resolve("Systems Library"))
                && java.nio.file.Files.isDirectory(base.resolve("Domain Libraries"));
    }

    private static boolean extractLibraryTo(java.nio.file.Path cache) {
        try {
            java.util.List<String> entries = new java.util.ArrayList<>();
            java.io.InputStream idx = RoundTrip.class.getClassLoader()
                    .getResourceAsStream("windtrader-lib/manifest.txt");
            if (idx != null) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(idx, java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isBlank()) entries.add(line.trim());
                }
                br.close();
            } else {
                return false;
            }
            for (String entry : entries) {
                java.io.InputStream in = RoundTrip.class.getClassLoader()
                        .getResourceAsStream("windtrader-lib/" + entry);
                if (in == null) continue;
                java.nio.file.Path out = cache.resolve(entry);
                java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                in.close();
            }
            return true;
        } catch (Exception e) {
            System.err.println("warning: extractLibraryTo: " + e.getMessage());
            return false;
        }
    }

    /**
     * Batch export: read newline-separated .sysml paths from stdin, export each to
     * {@code <path>.export.json} next to the source. Loads the standard library once
     * (single JVM) — the fast path for golden-manifest generation and CI regression.
     * Exit 0 if all files exported; runtime exit if any failed.
     */
    private static void runBatchExport() {
        Injector injector = setupInjector();
        IParser parser = injector.getInstance(IParser.class);

        java.util.List<String> paths = new java.util.ArrayList<>();
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(java.lang.System.in, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) paths.add(line.trim());
            }
        } catch (java.io.IOException e) {
            System.err.println("error: msg=read file list: " + e.getMessage());
            System.exit(EXIT_RUNTIME);
            return;
        }
        if (paths.isEmpty()) {
            System.err.println("error: msg=batch-export: no file paths on stdin.");
            System.exit(EXIT_INVALID);
            return;
        }

        int fails = 0;
        final java.io.PrintStream realOut = System.out;
        for (String path : paths) {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                        java.nio.charset.StandardCharsets.UTF_8);
                IParseResult pr = parser.parse(new StringReader(text));
                if (pr == null || pr.hasSyntaxErrors()) {
                    System.err.println("FAIL " + path + " (syntax errors)");
                    fails++;
                    continue;
                }
                Object rootObj = pr.getRootASTElement();
                if (!(rootObj instanceof Element)) {
                    System.err.println("FAIL " + path + " (no root element)");
                    fails++;
                    continue;
                }
                String json = runJupyterExport(text, path);
                if (json == null) {
                    System.err.println("FAIL " + path + " (export returned null)");
                    fails++;
                    continue;
                }
                java.nio.file.Path out = java.nio.file.Paths.get(path + ".export.json");
                java.nio.file.Files.write(out, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                System.setOut(realOut);
                realOut.println("OK " + path + " -> " + out + " (" + json.length() + " bytes)");
            } catch (Throwable t) {
                System.err.println("FAIL " + path + ": " + t.getMessage());
                t.printStackTrace(System.err);
                fails++;
            } finally {
                System.setOut(realOut);
            }
        }
        System.err.println("batch-export complete: " + paths.size() + " files, " + fails + " failed.");
        if (fails > 0) {
            System.exit(EXIT_RUNTIME);
        }
    }

    private static String readBuildSysmlVersion() {
        // Populated from a filtered resource in the jar (see pom.xml change below)
        try (InputStream in = RoundTrip.class.getClassLoader().getResourceAsStream("windtrader.properties")) {
            if (in == null) return "unknown";
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("sysml_version");
            return (v == null || v.isBlank()) ? "unknown" : v.trim();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void printVersions() {
        System.out.println("name=windtrader-java");
        System.out.println("mode=validator");
        System.out.println("validation=parse-only");
        System.out.println("capability=check,echo,export:json,batch-export:json");
        System.out.println("java_min=21");
        System.out.println("sysml_version=" + readBuildSysmlVersion());
    }

    public static void main(String[] args) {
        String cmd = (args.length == 0) ? "check" : args[0];

        try {
            if ("versions".equals(cmd)) {
                printVersions();
                System.exit(EXIT_OK);
                return;
            }

            if ("batch-export".equals(cmd)) {
                runBatchExport();
                System.exit(EXIT_OK);
                return;
            }

            if (!"check".equals(cmd) && !"echo".equals(cmd) && !"export".equals(cmd)) {
                usage();
                System.exit(EXIT_RUNTIME);
                return;
            }

            String input = readAllStdin();
            Injector injector = setupInjector();

            IParser parser = injector.getInstance(IParser.class);
            IParseResult pr = parser.parse(new StringReader(input));

            if (pr == null || pr.hasSyntaxErrors()) {
                printSyntaxErrors(pr);
                System.exit(EXIT_INVALID);
                return;
            }

            if (pr.getRootASTElement() == null) {
                System.err.println("error: msg=Parsed successfully but produced no root AST element.");
                System.exit(EXIT_INVALID);
                return;
            }

            if ("echo".equals(cmd)) {
                // Round-trip fidelity: emit the ORIGINAL input text (parse -> echo ->
                // re-parse yields identical canonical form). We deliberately do not print
                // a serialized/pretty form, which would not re-parse identically.
                // Note: a trailing newline is appended when the input lacks one, so the
                // output is newline-terminated, not byte-exact to input.
                System.out.print(input);
                if (!input.endsWith("\n")) {
                    System.out.println();
                }
            } else if ("export".equals(cmd)) {
                // Delegate to the pilot's Jupyter-faithful export path
                // (SysMLInteractive.export). This reproduces the pilot's %export output
                // exactly — the expanded element graph with populated references —
                // and avoids the SysML2JSON CLI's transformAll NPE on control-flow /
                // cross-resource models. See Westfall-io/windtrader-java#11.
                String json = runJupyterExport(input);
                if (json == null) {
                    System.exit(EXIT_RUNTIME);
                    return;
                }
                System.out.println(json);
            }

            System.exit(EXIT_OK);

        } catch (ParseException e) {
            System.err.println("error: msg=" + e.getMessage());
            System.exit(EXIT_INVALID);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(EXIT_RUNTIME);
        }
    }
}
