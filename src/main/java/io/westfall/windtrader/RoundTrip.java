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
            java.util.List<String> exportNames = new java.util.ArrayList<>();
            if (root != null) {
                String qn = root.getQualifiedName();
                if (qn != null && !qn.isBlank()) {
                    exportNames.add(qn);
                } else if (root instanceof org.omg.sysml.lang.sysml.Namespace) {
                    // The file root namespace is unnamed (typical for a file with
                    // multiple top-level packages/definitions). Export EVERY named
                    // top-level member — never silently drop later roots (e.g. a
                    // RootPackageTest with P1/P2/P3 must keep all three, and an
                    // AHFProfileLib that also declares AHFProfileMetadata must not
                    // truncate to the first). Each goes through the same Jupyter
                    // faithful export(name) path; their element arrays are merged.
                    for (Element m : ((org.omg.sysml.lang.sysml.Namespace) root).getOwnedMember()) {
                        String mn = m.getName();
                        if (mn != null && !mn.isBlank()) {
                            exportNames.add(mn);
                        }
                    }
                }
            }
            if (exportNames.isEmpty()) {
                System.err.println("error: msg=export: cannot determine an exportable root element name.");
                return null;
            }
            // Merge the JSON arrays for every named top-level member into one flat
            // array, DEDUPED by element @id. Multiple roots legitimately overlap
            // (e.g. AHFProfileLib + AHFProfileMetadata, where the metadata package
            // imports the library package and re-exports its elements) — emitting
            // the same element twice would be duplicate EMF copies. We keep the
            // first occurrence of each @id. export(name) may itself return an
            // ERROR: string for a member it cannot resolve — fail loudly rather
            // than concatenate an error payload.
            StringBuilder merged = new StringBuilder();
            merged.append('[');
            int written = 0;
            java.util.Set<String> seenIds = new java.util.HashSet<>();
            for (String name : exportNames) {
                Object exported = interactive.export(name, java.util.Collections.emptyList());
                if (exported == null) {
                    System.err.println("error: msg=export: pilot export returned null for '" + name + "'.");
                    return null;
                }
                String json = String.valueOf(exported);
                if (json.startsWith("ERROR:")) {
                    System.err.println("error: msg=export: pilot could not resolve root element '" + name + "': " + json.trim());
                    return null;
                }
                // json is a JSON array "[...]"; strip brackets and splice contents,
                // dropping elements whose @id was already emitted.
                String inner = json.trim();
                if (inner.startsWith("[")) inner = inner.substring(1);
                if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
                // Split top-level array items (they are JSON objects, so a simple
                // brace-depth scan is safe — no braces inside strings in this output).
                java.util.List<String> items = splitJsonArrayItems(inner);
                for (String item : items) {
                    String id = extractId(item);
                    if (id != null && !seenIds.add(id)) {
                        continue; // duplicate element — drop
                    }
                    if (written > 0) merged.append(',');
                    merged.append(item);
                    written++;
                }
            }
            merged.append(']');
            return merged.toString();
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            return null;
        } finally {
            System.setOut(realOut);
        }
    }

    /**
     * Split a JSON array body (text between {@code [} and {@code ]}) into its
     * top-level items. Scans character-by-character, tracking string literals
     * (with escape sequences) so braces inside strings are ignored, and brace
     * depth so nested objects within an item do not create false item boundaries.
     * An empty/blank body yields an empty list.
     */
    private static java.util.List<String> splitJsonArrayItems(String body) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String s = body;
        int depth = 0;
        int start = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // skip escaped char
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    out.add(s.substring(start, i + 1).trim());
                    // skip the comma separator after a complete item
                    int j = i + 1;
                    while (j < s.length() && (s.charAt(j) == ',' || Character.isWhitespace(s.charAt(j)))) {
                        j++;
                    }
                    start = j;
                    i = j - 1;
                }
            }
        }
        return out;
    }

    /**
     * Extract the {@code "@id"} string from a single JSON object item, or
     * {@code null} if absent. Used for element-identity dedup on merge.
     */
    private static String extractId(String item) {
        // The pilot's element JSON is {"payload": {"@id": "...", ...}, ...}; the
        // @id lives in payload (and @identity may repeat it). Search for the payload's
        // "@id" key — a JSON string value — with a tight regex over the item text.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"@id\"\\s*:\\s*\"([^\"]*)\"").matcher(item);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Preload a model's same-directory sibling {@code .sysml} imports into the
     * interactive's resource set, in dependency (topological) order, mirroring how
     * Jupyter accumulates parsed cells so a later cell's {@code import Sibling::*}
     * resolves.
     *
     * <p>A sibling is any {@code .sysml} file next to {@code sourcePath} that provides
     * a namespace the target transitively imports. A namespace maps to a provider file
     * either by the file-stem heuristic ({@code Import Foo::*} → {@code Foo.sysml}) or —
     * when no such file but a sibling declares the namespace as a top-level member —
     * by scanning siblings (this is how {@code import AHFNorway::*} resolves to
     * {@code AHFNorwayTopics.sysml}, where the {@code AHFNorway} namespace lives).
     * Files are visited once (deduped) in post-order, so a diamond import chain never
     * processes a sibling twice (which would create duplicate EMF element copies).
     * Standard-library namespaces are provided by {@code loadLibrary} and are not
     * re-processed here.
     */
    private static void preloadSiblingImports(SysMLInteractive interactive, String sourcePath) {
        try {
            java.nio.file.Path src = java.nio.file.Paths.get(sourcePath).toAbsolutePath().normalize();
            java.util.Set<String> visited = new java.util.HashSet<>();
            java.util.List<String> order = new java.util.ArrayList<>();
            resolveImports(src.toString(), visited, order);
            // order is post-order (deps before dependents); drop the target itself
            for (String sibling : order) {
                if (sibling.equals(src.toString())) {
                    continue;
                }
                String txt = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(sibling)),
                        java.nio.charset.StandardCharsets.UTF_8);
                interactive.process(txt);
            }
        } catch (Throwable t) {
            // Preload is best-effort: a missing/unreadable sibling must not abort the
            // export (the pilot's export() will surface the real resolution error if any).
        }
    }

    /**
     * Post-order DFS over the transitive same-directory sibling imports of
     * {@code path}, appending each file to {@code order} exactly once, dependencies
     * before dependents. Post-order visits deps, then adds {@code path} itself — never
     * a dep after recursing — so a diamond (two paths to the same sibling) yields the
     * sibling once. {@code visited} is keyed on normalized absolute paths, making the
     * result independent of how the path was spelled (relative vs absolute, {@code .}/
     * {@code ..}) and immune to import cycles re-entering the target.
     */
    private static void resolveImports(String path, java.util.Set<String> visited, java.util.List<String> order) {
        java.nio.file.Path abs = java.nio.file.Paths.get(path).toAbsolutePath().normalize();
        if (!visited.add(abs.toString())) {
            return;
        }
        try {
            String text = new String(java.nio.file.Files.readAllBytes(abs), java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Path dir = abs.getParent();
            for (String ns : importedNamespaces(text)) {
                java.nio.file.Path provider = findProviderFile(dir, ns, abs);
                if (provider != null && !provider.equals(abs)) {
                    resolveImports(provider.toString(), visited, order);
                }
            }
        } catch (Throwable t) {
            // best-effort
        }
        order.add(abs.toString());
    }

    /**
     * Locate the sibling {@code .sysml} file (next to {@code dir}) that provides the
     * top-level namespace {@code ns}, excluding {@code self}. First tries the
     * file-stem heuristic ({@code ns + ".sysml"}); if that file is absent, scans every
     * sibling for a top-level declaration of {@code ns}. Returns {@code null} when no
     * sibling provides it (e.g. a standard-library namespace, or a genuine unresolvable
     * import). A provider found by scanning is cached per-directory so the scan runs
     * once per directory, not once per import.
     */
    private static java.nio.file.Path findProviderFile(java.nio.file.Path dir, String ns, java.nio.file.Path self) {
        java.nio.file.Path stem = dir.resolve(ns + ".sysml");
        if (java.nio.file.Files.exists(stem)) {
            return stem.toAbsolutePath().normalize();
        }
        // Scan once per directory: map every top-level namespace -> its provider file.
        // The index is built over ALL siblings (including `self`), because the cache
        // key is the directory only — excluding `self` at build time would permanently
        // hide whichever file first triggered the scan from later lookups in the same
        // JVM. We filter the target out at lookup time below instead.
        java.util.Map<String, java.nio.file.Path> nsToFile = providerIndexCache.get(dir.toString());
        if (nsToFile == null) {
            java.util.Map<String, java.nio.file.Path> found = new java.util.HashMap<>();
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".sysml"))
                     .forEach(p -> {
                         try {
                             String txt = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                             for (String top : topLevelNamespaces(txt)) {
                                 found.putIfAbsent(top, p.toAbsolutePath().normalize());
                             }
                         } catch (Exception ignore) { }
                     });
            } catch (Exception ignore) { }
            nsToFile = found;
            providerIndexCache.put(dir.toString(), nsToFile);
        }
        java.nio.file.Path provider = nsToFile.get(ns);
        return (provider != null && !provider.equals(self)) ? provider : null;
    }

    /** Per-directory cache of top-level namespace -> provider file (scan at most once per dir). */
    private static final java.util.Map<String, java.util.Map<String, java.nio.file.Path>> providerIndexCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Extract the top-level namespaces declared in {@code text}: the first identifier
     * after a {@code package}, {@code library package} or package-like keyword at
     * column 0, plus bare {@code part def X} / {@code action def X} definitions.
     * Used only to map an imported namespace to the sibling file that provides it.
     */
    private static java.util.List<String> topLevelNamespaces(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?m)^\\s*(?:public|private|protected)?\\s*(?:library\\s+)?(?:package|part\\s+def|action\\s+def|metadata\\s+def|item\\s+def|port\\s+def|interface\\s+def|attribute\\s+def|class\\s+def|usage|definition)\\s+([A-Za-z_][A-Za-z0-9_']*)").matcher(text);
        while (m.find()) {
            String nm = m.group(1);
            if (!out.contains(nm)) {
                out.add(nm);
            }
        }
        return out;
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
