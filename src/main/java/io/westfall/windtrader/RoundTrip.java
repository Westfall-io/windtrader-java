// SPDX-License-Identifier: EPL-2.0
package io.westfall.windtrader;

import com.google.inject.Injector;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.parser.IParser;
import org.eclipse.xtext.parser.ParseException;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.util.traversal.Traversal;
import org.omg.sysml.util.traversal.facade.impl.JsonElementProcessingFacade;
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

    /**
     * Parse stdin (already read into {@code input}) as SysML and emit the SysMLv2
     * element JSON graph (API shape) to stdout, using the pilot's own
     * JsonElementProcessingFacade + Traversal.
     *
     * Preparatory steps mirror the pilot's Jupyter pipeline: load the SysML standard
     * library (so implicit-type inference can resolve library types like the "merge"
     * feature that SuccessionAsUsageAdapter needs — without it models with merge-node
     * successions NPE inside FeatureUtil.chainFeatures), attach the parsed root to an
     * EMF Resource (the facade's isStandardLibraryElement requires eResource() != null),
     * then resolve and transform.
     *
     * The whole operation runs with System.out redirected to a discard stream: the
     * pilot writes things to stdout that would corrupt the pure-JSON contract
     * (loadLibrary's "Reading <path>..." per library file, and the facade's '.' dots
     * every 100 elements). Only the final JSON (a String) is emitted after stdout
     * restores.
     */
    private static void runExport(String input, IParser parser) {
        java.io.PrintStream realOut = System.out;
        try {
            String json = exportOne(input, parser);
            if (json != null) realOut.println(json);
        } finally {
            System.setOut(realOut);
        }
    }

    /**
     * Export one SysML source to its API-shaped element JSON. Returns the JSON string,
     * or {@code null} on parse failure / missing library. Mirrors the pilot's Jupyter
     * pipeline: load the standard library (once per process — callers should share the
     * interactive across inputs), attach the root to a Resource, resolve + transform,
     * then run the JsonElementProcessingFacade + Traversal.
     *
     * Sets System.out to a discard stream for the duration (loadLibrary prints
     * "Reading <path>..." and the facade prints '.' progress dots; both would corrupt
     * pure-JSON output). The caller owns restoring System.out.
     */
    private static String exportOne(String input, IParser parser) {
        SysMLInteractive interactive = SysMLInteractive.getInstance();

        // Locate the bundled SysML standard library: extract from the jar if not
        // already extracted, else use the override from WINDTRADER_SYSML_LIBRARY.
        String libDir = bundledLibraryDir();
        if (libDir == null) {
            System.err.println("error: msg=SysML standard library unavailable; cannot export.");
            return null;
        }

        // Everything below pollutes stdout; discard it. The caller restores System.out.
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        try {
            interactive.loadLibrary(libDir);

            org.eclipse.emf.ecore.resource.Resource res =
                    interactive.getResourceSet().createResource(
                            org.eclipse.emf.common.util.URI.createURI("windtrader-export.sysml"));
            IParseResult pr2 = parser.parse(new StringReader(input));
            if (pr2 == null || pr2.hasSyntaxErrors()) {
                printSyntaxErrors(pr2);
                return null;
            }
            Element root = (Element) pr2.getRootASTElement();
            if (root == null) {
                System.err.println("error: msg=Parsed successfully but produced no root AST element.");
                return null;
            }
            res.getContents().add(root);

            interactive.addResourceToIndex(res);
            try {
                interactive.resolveAllInputResources();
            } catch (Exception rerr) {
                System.err.println("warning: resolveAllInputResources: " + rerr.getMessage());
            }
            try {
                interactive.transformAll(true);
            } catch (Exception terr) {
                System.err.println("warning: transformAll: " + terr.getMessage());
            }

            JsonElementProcessingFacade facade = new JsonElementProcessingFacade();
            Traversal traversal = new Traversal(facade, true);
            traversal.visit(root);
            facade.postProcess(root);
            return facade.toJson(true);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
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
            return override;
        }
        try {
            java.nio.file.Path cache = java.nio.file.Paths.get(
                    System.getProperty("user.home"), ".cache", "windtrader", "sysml-library");
            java.nio.file.Path kernelDir = cache.resolve("Kernel Libraries");
            boolean haveKernel = java.nio.file.Files.isDirectory(kernelDir);
            // A jar-resource bundle marker tells us whether resources exist to extract.
            boolean haveResources = RoundTrip.class.getClassLoader()
                    .getResource("windtrader-lib/marker.txt") != null;
            if (haveKernel) {
                return cache.toString();
            }
            if (haveResources && extractLibraryTo(cache)) {
                return cache.toString();
            }
            return null;
        } catch (Exception e) {
            System.err.println("warning: bundledLibraryDir: " + e.getMessage());
            return null;
        }
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
        final java.io.PrintStream realOut = System.out; // capture real stdout before exportOne redirects
        for (String path : paths) {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                        java.nio.charset.StandardCharsets.UTF_8);
                String json = exportOne(text, parser);
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

            if (!"check".equals(cmd) && !"echo".equals(cmd) && !"export".equals(cmd) && !"batch-export".equals(cmd)) {
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
                runExport(input, parser);
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
