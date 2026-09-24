// SPDX-License-Identifier: EPL-2.0
package io.westfall.windtrader;

import com.google.inject.Injector;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.parser.IParser;
import org.eclipse.xtext.parser.ParseException;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.xtext.SysMLStandaloneSetupGenerated;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Parse-only validator for SysML v2 text.
 *
 * <p>Before parsing, we bootstrap {@link SysMLInteractive#getInstance()} so the pilot's
 * full EMF/EPackage feature-wiring is registered. Without it, a bare
 * {@link SysMLStandaloneSetupGenerated} parse throws
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
        // complete wiring the grammar needs.
        SysMLInteractive.getInstance();
        return new SysMLStandaloneSetupGenerated().createInjectorAndDoEMFRegistration();
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

            if (!"check".equals(cmd) && !"echo".equals(cmd)) {
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
                System.out.print(input);
                if (!input.endsWith("\n")) {
                    System.out.println();
                }
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
