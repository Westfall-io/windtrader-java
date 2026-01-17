package io.westfall.windtrader;

import com.google.inject.Injector;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.parser.IParser;
import org.eclipse.xtext.parser.ParseException;
import org.omg.sysml.xtext.SysMLStandaloneSetupGenerated;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

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

    private static void forceInitEPackage(String className) {
        try {
            Class.forName(className, true, RoundTrip.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
            // OK: some packages differ by distribution
        } catch (Throwable t) {
            throw new RuntimeException("Failed to init EPackage: " + className, t);
        }
    }

    private static void initEPackages() {
        // Critical: register SysML generated package (fixes "Unresolved proxy ... Namespace")
        forceInitEPackage("org.omg.sysml.lang.sysml.SysMLPackage");

        // Common supporting packages (datatype references like String)
        forceInitEPackage("org.eclipse.uml2.types.TypesPackage");
        forceInitEPackage("org.eclipse.uml2.uml.UMLPackage");

        // KerML package names vary; try a few (safe if missing)
        forceInitEPackage("org.omg.kerml.lang.kerml.KerMLPackage");
        forceInitEPackage("org.omg.kerml.lang.kerml.KermlPackage");
        forceInitEPackage("org.omg.kerml.lang.KerMLPackage");
        forceInitEPackage("org.omg.kerml.lang.KermlPackage");
    }

    private static Injector setupInjector() {
        // IMPORTANT: init generated packages FIRST (correct factories, correct nsURI registration)
        initEPackages();

        // Then do Xtext registrations
        new org.omg.kerml.xtext.KerMLStandaloneSetupGenerated().createInjectorAndDoEMFRegistration();
        return new SysMLStandaloneSetupGenerated().createInjectorAndDoEMFRegistration();
    }

    private static void printSyntaxErrors(IParseResult pr) {
        if (pr == null) return;

        Iterable<INode> errs = pr.getSyntaxErrors();
        if (errs == null) return;

        for (INode n : errs) {
            int line = n.getStartLine();
            int offset = n.getOffset();
            String text = n.getText();
            if (text != null) text = text.replace("\n", "\\n");
            System.err.println("error: line=" + line + " offset=" + offset + " near=" + text);
        }
    }

    public static void main(String[] args) {
        String cmd = (args.length == 0) ? "check" : args[0];

        try {
            if ("versions".equals(cmd)) {
                System.out.println("name=windtrader-java");
                System.out.println("mode=validator");
                System.out.println("validation=parse-only");
                System.out.println("java_min=21");
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
                System.out.print(pr.getRootNode().getText());
            }

            System.exit(EXIT_OK);

        } catch (ParseException e) {
            System.err.println("error: msg=" + e.getMessage());
            System.exit(EXIT_INVALID);
        } catch (Throwable t) {
            // Show the real nested reason; your current output is hiding it behind "msg="
            t.printStackTrace(System.err);
            System.exit(EXIT_RUNTIME);
        }
    }
}
