package com.github.redhatqe.polarizer.reporter;

import com.github.redhatqe.polarizer.reporter.configuration.Serializer;
import com.github.redhatqe.polarizer.reporter.configuration.XUnitInfo;
import com.github.redhatqe.polarizer.reporter.configuration.data.XUnitConfig;
import com.github.redhatqe.polarizer.reporter.exceptions.*;
import com.github.redhatqe.polarizer.reporter.jaxb.IJAXBHelper;
import com.github.redhatqe.polarizer.reporter.jaxb.JAXBHelper;
import com.github.redhatqe.polarizer.reporter.jaxb.JAXBReporter;
import com.github.redhatqe.polarizer.reporter.utils.FileHelper;
import com.github.redhatqe.polarizer.reporter.utils.Tuple;
import com.github.redhatqe.polarizer.reporter.importer.xunit.*;
import com.github.redhatqe.polarizer.reporter.importer.xunit.Error;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.*;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;


/**
 * Class that handles junit report generation for TestNG
 *
 * Use this class when running TestNG tests as -reporter XUnitReporter.  It can be
 * configured through the polarize-config.xml file.  A default configuration is contained in the resources folder, but
 * a global environment variable of XUNIT_IMPORTER_CONFIG can also be set.  If this env var exists and it points to a
 * file, this file will be loaded instead.
 */
public class XUnitReporter implements IReporter {
    private final static Logger logger = LogManager.getLogger(XUnitReporter.class);
    public static String configPath;
    public static File cfgFile = null;
    private static XUnitConfig config;
    private final static File defaultPropertyFile =
            new File(System.getProperty("user.home") + "/.polarize/reporter.properties");
    private static List<String> failedSuites = new ArrayList<>();

    public final static String templateId = "polarion-testrun-template-id";
    public final static String testrunId = "polarion-testrun-id";
    public final static String testrunTitle = "polarion-testrun-title";
    public final static String testrunType = "polarion-testrun-type-id";
    public final static String polarionCustom = "polarion-custom";
    public final static String polarionResponse = "polarion-response";
    public final static String polarionGroupId = "polarion-group-id";
    public final static String polarionProjectId = "polarion-project-id";
    public final static String polarionUserId = "polarion-user-id";

    private File bad = new File("/tmp/bad-tests.txt");

    public static void setXUnitConfig(String path) throws IOException {
        if (path == null || path.equals(""))
            return;
        File cfgFile = new File(path);
        XUnitReporter.config = Serializer.fromYaml(XUnitConfig.class, cfgFile);
        logger.info("Set XUnitReporter config to " + path);
    }

    public static XUnitConfig getConfig(String path) {
        if (path != null) {
            cfgFile = new File(path);
            configPath = path;
        }
        else if (System.getProperty("polarize.config") != null) {
            configPath = System.getProperty("polarize.config");
            cfgFile = new File(configPath);
        }
        else {
            Path phome = Paths.get(System.getProperty("user.home"), ".polarizer", "polarizer-xunit.yml");
            cfgFile = new File(phome.toString());
            configPath = phome.toString();
        }

        if (!cfgFile.exists()) {
            logger.warn("Using a dummy default config file");
            //throw new NoConfigFoundError(String.format("Could not config file %s", configPath));
            InputStream is = XUnitReporter.class.getClassLoader().getResourceAsStream("dummy-polarizer-xunit.yml");
            File tmp = FileHelper.makeTempFile("/tmp", "dummy-config", ".yml", "rw-rw----");
            FileOutputStream os = null;
            try {
                os = new FileOutputStream(tmp);
                int r = 0;
                byte[] buffer = new byte[1024];
                while ((r = is.read(buffer)) != -1) {
                    os.write(buffer, 0, r);
                }
            } catch (FileNotFoundException e) {
                logger.error("Could not find config file");
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (os != null) {
                    try {
                        os.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            cfgFile = tmp;
        }
        else {
            logger.info(String.format("Using %s for the xunit reporting config file", cfgFile.toString()));
        }

        try {
            config = Serializer.from(XUnitConfig.class, cfgFile);
        } catch (IOException e) {
            e.printStackTrace();
            throw new ConfigurationError("Could not serialize polarizer-xunit config file");
        }
        return config;
    }

    public static Properties getProperties() {
        Properties props = new Properties();
        Map<String, String> envs = System.getenv();
        if (envs.containsKey("XUNIT_IMPORTER_CONFIG")) {
            String path = envs.get("XUNIT_IMPORTER_CONFIG");
            File fpath = new File(path);
            if (fpath.exists()) {
                try {
                    FileInputStream fis = new FileInputStream(fpath);
                    props.load(fis);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        else if (XUnitReporter.defaultPropertyFile.exists()){
            try {
                FileInputStream fis = new FileInputStream(XUnitReporter.defaultPropertyFile);
                props.load(fis);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        else {
            InputStream is = XUnitReporter.class.getClassLoader().getResourceAsStream("reporter.properties");
            try {
                props.load(is);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return props;
    }

    public void setTestSuiteResults(Testsuite ts, FullResult fr, ITestContext ctx) {
        if (fr == null)
            return;

        if (fr.total != fr.fails + fr.errors + fr.skips + fr.passes) {
            String e = "Total number of tests run != fails + errors + skips + passes\n";
            String v = "                       %d !=    %d +     %d +    %d +     %d\n";
            v = String.format(v, fr.total, fr.fails, fr.errors, fr.skips, fr.passes);
            System.err.println(e + v);
        }
        int numErrors = fr.errorsByMethod.size();


        // The iterations feature of Polarion means that we don't need to specify how many times a permutation of a
        // method + args passed/failed/skipped etc.  That's why we don't directly use the fr numbers.
        int numFails = ctx.getFailedTests().size();
        int numSkips = ctx.getSkippedTests().size();
        int numTotal = ctx.getAllTestMethods().length;
        if (numErrors > 0)
            numFails = numFails - numErrors;
        if (numFails <= 0)
            numFails = 0;
        ts.setErrors(Integer.toString(numErrors));
        ts.setFailures(Integer.toString(numFails));
        ts.setSkipped(Integer.toString(numSkips));
        ts.setTests(Integer.toString(numTotal));
    }

    /**
     * Creates an xunit file compatible with the Polarion xunit importer service
     *
     * @param cfg contains arguments needed to convert xunit to polarion compatible xunit
     * @return a new File that is compatible
     */
    public static Optional<File> createPolarionXunit(XUnitConfig cfg) {
        File xunit = new File(cfg.getCurrentXUnit());
        File newXunit = FileHelper.makeTempFile("/tmp", "polarion-xunit-", ".xml", "rw-rw----");

        Map<String, Map<String, IdParams>> mapping = FileHelper.loadMapping(new File(cfg.getMapping()));
        String project = cfg.getProject();
        Function<String, IdParams> fn = (qual) -> {
            Map<String, IdParams> m = mapping.get(qual);
            if (m != null) {
                IdParams param =  m.get(project);
                if (param == null)
                    throw new MappingError(String.format("Could not find %s -> %s in mapping", qual, project));
                return param;
            }
            else
                throw new MappingError(String.format("Could not find %s in mapping", qual));
        };

        JAXBHelper jaxb = new JAXBHelper();
        Optional<Testsuites> maybeSuites;
        Optional<Testsuite> maybeSuite;
        Testsuite suite;
        maybeSuites = XUnitReporter.getTestSuitesFromXML(xunit);

        Consumer<Testcase> tcHdlr = tc -> {
            // Add the properties here
            String qual = String.format("%s.%s", tc.getClassname(), tc.getName());
            IdParams param = fn.apply(qual);
            com.github.redhatqe.polarizer.reporter.importer.xunit.Properties props =
                    tc.getProperties();
            if (props == null) {
                props = new com.github.redhatqe.polarizer.reporter.importer.xunit.Properties();
                tc.setProperties(props);
            }
            Property prop = new Property();
            prop.setName("polarion-testcase-id");
            prop.setValue(param.id);
            param.getParameters().forEach(p -> {
                Property tp = new Property();
                tp.setName(String.format("polarion-parameter-%s", p));
                tp.setValue("");
            });
            // mapping file wins
            List<Property> curr = props.getProperty();
            Boolean noTestCaseId = curr.stream().noneMatch(p -> p.getName().equals(prop.getName()));
            if (noTestCaseId)
                curr.add(prop);
            else {
                IntStream.range(0, curr.size())
                    .map(i -> curr.get(i).getName().equals(prop.getName()) ? i : -1)
                    .filter(x -> x != -1)
                    .forEach(e -> {
                        logger.info(String.format("Using mapping.json TestCase ID value of %s", prop.getValue()));
                        curr.set(e, prop);
                    });
            }
        };

        if (!maybeSuites.isPresent())
            throw new XMLUnmarshallError(String.format("Could not unmarshall %s", xunit));
        Testsuites suites = maybeSuites.get();
        if (suites.getTestsuite().size() == 0) {
            maybeSuite = XUnitReporter.getTestSuiteFromXML(xunit);
            if (!maybeSuite.isPresent())
                throw new XMLUnmarshallError(String.format("Could not unmarshall %s as a TestSuite.class", xunit));
            suite = maybeSuite.get();
            suite.getTestcase().forEach(tcHdlr);
            suites.getTestsuite().clear();
            suites.getTestsuite().add(suite);
        }
        else {
            suites.getTestsuite()
                    .forEach(ts -> ts.getTestcase().forEach(tcHdlr));
        }

        com.github.redhatqe.polarizer.reporter.importer.xunit.Properties tsProps = suites.getProperties();
        if (tsProps == null) {
            tsProps = new com.github.redhatqe.polarizer.reporter.importer.xunit.Properties();
            suites.setProperties(tsProps);
        }
        List<Property> sProps = suites.getProperties().getProperty();
        XUnitReporter.setPropsFromConfig(cfg, sProps);
        IJAXBHelper.marshaller(suites, newXunit, jaxb.getXSDFromResource(Testsuites.class));
        cfg.setNewXunit(newXunit.toString());

        return Optional.of(newXunit);
    }

    /**
     * Replaces or adds a Property to the props list
     *
     * @param props
     * @param prop
     */
    private static void setProp(List<Property> props, Property prop) {
        props.stream()
            .map(p -> new Tuple<>(p.getName(), p))
            .filter(p -> p.first.equals(prop.getName()))
            .filter(p -> !p.second.getValue().equals(prop.getValue()))
            .forEach(p -> {
                String oVal = p.second.getValue();
                logger.info(String.format("Overwriting original value %s to %s for %s", oVal, prop.getValue(), p.first));
                p.second.setValue(prop.getValue());
            });
    }

    private static void addProp(List<Property> props, Property prop) {
        Boolean matched = props.stream()
            .map(p -> new Tuple<>(p.getName(), p))
            .anyMatch(p -> p.first.equals(prop.getName()));
        if (!matched) {
            logger.info(String.format("Adding %s = %s to Property list", prop.getName(), prop.getValue()));
            props.add(prop);
        }
    }

    public static void setPropsFromConfig(XUnitConfig cfg, List<Property> props) {
        //props.clear();
        Set<String> propSet = props.stream().map(Property::getName).collect(Collectors.toSet());
        XUnitInfo info = cfg.getXunit();

        BiFunction<String, Map<String, String>, List<Property>> fn = (s, m) -> m.entrySet().stream()
            .filter(e -> !propSet.contains(e.getKey()))
            .map(es -> {
                Property prop = new Property();
                prop.setName(s + es.getKey());
                prop.setValue(es.getValue());
                return prop;
            })
            .collect(Collectors.toList());

        List<Property> custProps = fn.apply("polarion-custom-", info.getCustom().getProperties());
        // Overwrite existing Properties
        custProps.forEach(cp -> XUnitReporter.setProp(props, cp));
        // If Property does not exist in list, add it
        custProps.forEach(cp -> XUnitReporter.addProp(props, cp));

        List<Property> tsProps = info.getCustom().getTestSuite().entrySet().stream()
            .map((Map.Entry<String, Boolean> es) -> {
                Property prop = new Property();
                prop.setName("polarion-" + es.getKey());
                prop.setValue(es.getValue().toString());
                return prop;
            })
            .collect(Collectors.toList());

        BiFunction<String, Supplier<String>, Property> con = (s, fun) -> {
            Property prop = new Property();
            prop.setName(s);
            prop.setValue(fun.get());
            return prop;
        };

        // Miscellaneous
        List<Tuple<String, Supplier<String>>> zip = new ArrayList<>();
        zip.add(new Tuple<>(XUnitReporter.testrunId, () -> info.getTestrun().getId()));
        zip.add(new Tuple<>(XUnitReporter.templateId, () -> info.getTestrun().getTemplateId()));
        zip.add(new Tuple<>(XUnitReporter.testrunTitle, () -> info.getTestrun().getTitle()));
        zip.add(new Tuple<>(XUnitReporter.testrunType, () -> info.getTestrun().getType()));
        zip.add(new Tuple<>(XUnitReporter.polarionGroupId, () -> info.getTestrun().getGroupId()));
        zip.add(new Tuple<>(XUnitReporter.polarionProjectId, cfg::getProject));
        String polResp = String.format("polarion-response-%s", info.getSelector().getName());
        zip.add(new Tuple<>(polResp, () -> info.getSelector().getValue()));
        zip.add(new Tuple<>(XUnitReporter.polarionUserId, () -> cfg.getServers().get("polarion").getUser()));

        List<Property> miscProps = zip.stream()
            .map(t -> con.apply(t.first, t.second))
            .collect(Collectors.toList());

        miscProps.forEach(mp -> {
            XUnitReporter.setProp(props, mp);
            XUnitReporter.addProp(props, mp);
        });
    }

    /**
     * Generates a modified xunit result that can be used for the XUnit Importer
     *
     * Example of a modified junit file:
     *
     * @param xmlSuites passed by TestNG
     * @param suites passed by TestNG
     * @param outputDirectory passed by TestNG.  configurable?
     */
    @Override
    public void generateReport(List<XmlSuite> xmlSuites, List<ISuite> suites, String outputDirectory) {
        String p = System.getProperty("polarize.config");
        XUnitConfig config = XUnitReporter.getConfig(p);
        Testsuites tsuites = XUnitReporter.initTestSuiteInfo(config.getXunit().getSelector().getName());
        List<Testsuite> tsuite = tsuites.getTestsuite();

        if (this.bad.exists()) {
            try {
                Files.delete(this.bad.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Get information for each <testsuite>
        suites.forEach(suite -> {
            // suite here for the rhsm-qe tests should only be one occurrence
            Map<String, ISuiteResult> results = suite.getResults();
            Map<String, Tuple<FullResult, List<Testcase>>> full = XUnitReporter.getMethodInfo(suite, this.bad);
            List<Testsuite> collected = results.entrySet().stream()
                    .map(es -> {
                        // the results that we iterate through is each <test> element from the suite.xml.  From our
                        // perspective each <testsuite> is effectively the <test>, and in turn we model each <test>
                        // as a Class in java
                        Testsuite ts = new Testsuite();
                        List<Testcase> tests = ts.getTestcase();
                        if (tests == null)
                            tests = new ArrayList<>();

                        String key = es.getKey();
                        ISuiteResult result = es.getValue();
                        ITestContext ctx = result.getTestContext();
                        XmlTest xt = ctx.getCurrentXmlTest();
                        List<XmlClass> clses = xt.getClasses();
                        Set<String> clsSet = clses.stream()
                                .map(x -> {
                                    String sptCls = x.getSupportClass().toString();
                                    sptCls = sptCls.replace("class ", "");
                                    return sptCls;
                                })
                                .collect(Collectors.toSet());

                        ts.setName(key);
                        Date start = ctx.getStartDate();
                        Date end = ctx.getEndDate();
                        double duration = (end.getTime() - start.getTime()) / 1000.0;
                        ts.setTime(Double.toString(duration));

                        // While I suppose it's possible, we should have only one or zero possible results from the map
                        // so findFirst should return at most 1.  When will we have zero?
                        List<Tuple<FullResult, List<Testcase>>> frList = full.entrySet().stream()
                                .filter(e -> {
                                    String cls = e.getKey();
                                    return clsSet.contains(cls);
                                })
                                .map(Map.Entry::getValue)
                                .collect(Collectors.toList());
                        Optional<Tuple<FullResult, List<Testcase>>> maybeFR = frList.stream().findFirst();
                        Tuple<FullResult, List<Testcase>> tup = maybeFR.orElse(new Tuple<>());
                        FullResult fr = tup.first;
                        List<Testcase> tcs = tup.second;
                        if (tcs != null)
                            tests.addAll(tcs);

                        setTestSuiteResults(ts, fr, ctx);
                        if (fr == null) {
                            //System.out.println(String.format("Skipping test for %s", ctx.toString()));
                            return null;  // No FullResult due to empty frList.  Will be filtered out
                        }
                        else
                            return ts;
                    })
                    .filter(Objects::nonNull)  // filter out any suites without FullResult
                    .collect(Collectors.toList());

            tsuite.addAll(collected);
        });

        // Now that we've gone through the suites, let's marshall this into an XML file for the XUnit Importer
        FullResult suiteResults = getSuiteResults(tsuites);
        System.out.println(String.format("Error: %d, Failures: %d, Success: %d, Skips: %d", suiteResults.errors,
                suiteResults.fails, suiteResults.passes, suiteResults.skips));
        File reportPath = new File(outputDirectory + "/testng-polarion.xml");
        JAXBHelper jaxb = new JAXBHelper();
        IJAXBHelper.marshaller(tsuites, reportPath, jaxb.getXSDFromResource(Testsuites.class));
    }

    /**
     * Sets the status for a Testcase object given values from ITestResult
     * 
     * @param result
     * @param tc
     */
    private static void getStatus(ITestResult result, Testcase tc, FullResult fr, String qual) {
        Throwable t = result.getThrowable();
        int status = result.getStatus();
        StringBuilder sb = new StringBuilder();
        fr.total++;
        switch(status) {
            // Unfortunately, TestNG doesn't distinguish between an assertion failure and an error.  The way to check
            // is if getThrowable() returns non-null
            case ITestResult.FAILURE:
                if (t != null && !(t instanceof java.lang.AssertionError)) {
                    fr.errors++;
                    if (!fr.errorsByMethod.contains(qual))
                        fr.errorsByMethod.add(qual);
                    Error err = new Error();
                    String maybe = t.getMessage();
                    if (maybe != null) {
                        String msg = t.getMessage().length() > 128 ? t.getMessage().substring(128) : t.getMessage();
                        err.setMessage(msg);
                    }
                    else
                        err.setMessage("java.lang.NullPointerException");
                    Arrays.stream(t.getStackTrace()).forEach(st -> sb.append(st.toString()).append("\n"));
                    err.setContent(sb.toString());
                    tc.getError().add(err);
                }
                else {
                    fr.fails++;
                    Failure fail = new Failure();
                    if (t != null)
                        fail.setContent(t.getMessage());
                    tc.getFailure().add(fail);
                }
                break;
            case ITestResult.SKIP:
                fr.skips++;
                tc.setSkipped("true");
                break;
            case ITestResult.SUCCESS:
                fr.passes++;
                tc.setStatus("success");
                break;
            default:
                if (t != null) {
                    Error err = new Error();
                    err.setMessage(t.getMessage().substring(128));
                    err.setContent(t.getMessage());
                    tc.getError().add(err);
                }
                break;
        }
    }

    public static boolean
    checkMethInMapping(Map<String, IdParams> inner, String qual, String project, File badMethods) {
        boolean in = true;
        if (inner == null || !inner.containsKey(project)) {
            String err = String.format("%s does not exist in mapping file for Project %s \n", qual, project);
            logger.error(err);
            try {
                FileWriter badf = new FileWriter(badMethods, true);
                BufferedWriter bw = new BufferedWriter(badf);
                bw.write(err);
                bw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            in = false;
        }
        return in;
    }

    public FullResult getSuiteResults(Testsuites suites) {
        List<Testsuite> sList = suites.getTestsuite();
        return sList.stream()
                .reduce(new FullResult(),
                        (acc, s) -> {
                            acc.skips += Integer.parseInt(s.getSkipped());
                            acc.errors += Integer.parseInt(s.getErrors());
                            acc.fails += Integer.parseInt(s.getFailures());
                            acc.total += Integer.parseInt(s.getTests());
                            acc.passes = acc.passes + (acc.total - (acc.skips + acc.errors + acc.fails));
                            return acc;
                        },
                        FullResult::add);
    }

    /**
     * Gets information from each invoked method in the test suite
     *
     * @param suite suite that was run by TestNG
     * @return map of classname to a tuple of the FullResult and TestCase
     */
    private static Map<String, Tuple<FullResult, List<Testcase>>>
    getMethodInfo(ISuite suite, File badMethods) {
        List<IInvokedMethod> invoked = suite.getAllInvokedMethods();
        Map<String, Tuple<FullResult, List<Testcase>>> full = new HashMap<>();
        for(IInvokedMethod meth: invoked) {
            ITestNGMethod fn = meth.getTestMethod();
            if (!fn.isTest()) {
                continue;
            }

            ITestClass clz = fn.getTestClass();
            String methname = fn.getMethodName();
            String classname = clz.getName();

            // Load the mapping file
            String project = XUnitReporter.config.getProject();
            String path = XUnitReporter.config.getMapping();
            File fpath = new File(path);
            if (!fpath.exists()) {
                String err = String.format("Could not find mapping file %s", path);
                XUnitReporter.logger.error(err);
                throw new MappingError(err);
            }
            String qual = String.format("%s.%s", classname, methname);
            Map<String, Map<String, IdParams>> mapping = FileHelper.loadMapping(fpath);
            Map<String, IdParams> inner = mapping.get(qual);

            if (!checkMethInMapping(inner, qual, project, badMethods))
                continue;

            FullResult fres;
            Testcase testcase = new Testcase();
            List<Testcase> tests;
            if (!full.containsKey(classname)) {
                fres = new FullResult();
                tests = new ArrayList<>();
                tests.add(testcase);
                full.put(classname, new Tuple<>(fres, tests));
            }
            else {
                Tuple<FullResult, List<Testcase>> tup = full.get(classname);
                fres = tup.first;
                tests = tup.second;
                tests.add(testcase);
            }
            ITestResult result = meth.getTestResult();
            Double millis = (result.getEndMillis() - result.getStartMillis()) / 1000.0;

            fres.classname = classname;
            testcase.setTime(millis.toString());
            testcase.setName(methname);
            testcase.setClassname(classname);
            XUnitReporter.getStatus(result, testcase, fres, qual);

            // Create the <properties> element, and all the child <property> sub-elements from the iteration data.
            // Gets the IdParams from the mapping.json file which has all the parameter information
            IdParams ip = inner.get(project);
            String id = ip.getId();
            List<String> args = ip.getParameters();
            Property polarionID = XUnitReporter.createProperty("polarion-testcase-id", id);
            com.github.redhatqe.polarizer.reporter.importer.xunit.Properties props =
                    getPropertiesFromMethod(result, args, polarionID);
            testcase.setProperties(props);
        }
        return full;
    }

    /**
     * Takes the parameter info from the mapping.json file for the TestCase ID, and generates the Properties for it
     *
     * @param result
     * @param args The list of args obtained from mapping.json for the matching polarionID
     * @param polarionID The matching Property of a Polarion ID for a TestCase
     * @return
     */
    public static com.github.redhatqe.polarizer.reporter.importer.xunit.Properties
    getPropertiesFromMethod(ITestResult result, List<String> args, Property polarionID) {
        com.github.redhatqe.polarizer.reporter.importer.xunit.Properties props =
                new com.github.redhatqe.polarizer.reporter.importer.xunit.Properties();
        List<Property> tcProps = props.getProperty();
        tcProps.add(polarionID);

        // Get all the iteration data
        Object[] params = result.getParameters();
        if (args.size() != params.length) {
            String name = String.format("testname: %s, methodname: %s", result.getTestName(), result.getMethod().getMethodName());
            XUnitReporter.logger.error(String.format("Length of parameters from %s not the same as from mapping file", name));
            String argList = args.stream().reduce("", (acc, n) -> acc + n + ",");
            String err = String.format("While checking args = %s", argList);
            logger.error(err);
            throw new MappingError(err);
        }
        for(int x = 0; x < params.length; x++) {
            Property param = new Property();
            param.setName("polarion-parameter-" + args.get(x));
            String p;
            if (params[x] == null)
                p = "null";
            else
                p = params[x].toString();
            param.setValue(p);
            tcProps.add(param);
        }
        return props;
    }

    /**
     * Gets information from polarize-config to set as the elements in the <testsuites>
     *
     * @param responseName
     * @return
     */
    private static Testsuites initTestSuiteInfo(String responseName) {
        Testsuites tsuites = new Testsuites();
        com.github.redhatqe.polarizer.reporter.importer.xunit.Properties props =
                new com.github.redhatqe.polarizer.reporter.importer.xunit.Properties();
        List<Property> properties = props.getProperty();

        Property user = XUnitReporter.createProperty("polarion-user-id",
                config.getServers().get("polarion").getUser());
        properties.add(user);

        Property projectID = XUnitReporter.createProperty("polarion-project-id", config.getProject());
        properties.add(projectID);

        Map<String, Boolean> xprops = config.getXunit().getCustom().getTestSuite();
        Property testRunFinished = XUnitReporter.createProperty("polarion-set-testrun-finished",
                xprops.get("set-testrun-finished").toString());
        properties.add(testRunFinished);

        Property dryRun = XUnitReporter.createProperty("polarion-dry-run", xprops.get("dry-run").toString());
        properties.add(dryRun);

        Property includeSkipped = XUnitReporter.createProperty("polarion-include-skipped",
                xprops.get("include-skipped").toString());
        properties.add(includeSkipped);

        Configurator cfg = XUnitReporter.createConditionalProperty(XUnitReporter.polarionResponse, responseName,
                                                                   properties);
        cfg.set();
        cfg = XUnitReporter.createConditionalProperty(XUnitReporter.polarionCustom, null, properties);
        cfg.set();
        cfg = XUnitReporter.createConditionalProperty(XUnitReporter.testrunTitle, null, properties);
        cfg.set();
        cfg = XUnitReporter.createConditionalProperty(XUnitReporter.testrunId, null, properties);
        cfg.set();
        cfg = XUnitReporter.createConditionalProperty(XUnitReporter.templateId, null, properties);
        cfg.set();

        tsuites.setProperties(props);
        return tsuites;
    }

    /**
     * Simple setter for a Property
     *
     * TODO: replace this with a lambda
     *
     * @param name key
     * @param value value of the key
     * @return Property with the given name and value
     */
    private static Property createProperty(String name, String value) {
        Property prop = new Property();
        prop.setName(name);
        prop.setValue(value);
        return prop;
    }

    @FunctionalInterface
    interface Configurator {
        void set();
    }

    /**
     * Creates a Configurator functional interface useful to set properties for the XUnit importer
     *
     * @param name element name
     * @param value value for the element (might be attribute depending on XML element)
     * @param properties list of Property
     * @return The Configurator that can be used to set the given name and value
     */
    private static Configurator createConditionalProperty(String name, String value, List<Property> properties) {
        Configurator cfg;
        Property prop = new Property();
        prop.setName(name);

        switch(name) {
            case XUnitReporter.testrunType:
                cfg = () -> {
                    String trTypeId = config.getXunit().getTestrun().getType();
                    if (trTypeId.equals(""))
                        return;
                    prop.setValue(trTypeId);
                    properties.add(prop);
                };
                break;
            case XUnitReporter.templateId:
                cfg = () -> {
                    String tempId = config.getXunit().getTestrun().getTemplateId();
                    if (tempId.equals(""))
                        return;
                    prop.setValue(tempId);
                    properties.add(prop);
                };
                break;
            case XUnitReporter.testrunTitle:
                cfg = () -> {
                    String trTitle = config.getXunit().getTestrun().getTitle();
                    if (trTitle.equals(""))
                        return;
                    prop.setValue(trTitle);
                    properties.add(prop);
                };
                break;
            case XUnitReporter.testrunId:
                cfg = () -> {
                    String trId = config.getXunit().getTestrun().getId();
                    if (trId.equals(""))
                        return;
                    prop.setValue(trId);
                    properties.add(prop);
                };
                break;
            case XUnitReporter.polarionResponse:
                cfg = () -> {
                    String selVal = config.getXunit().getSelector().getValue();
                    if (selVal.equals(""))
                        return;
                    prop.setName(XUnitReporter.polarionResponse + "-" + value);
                    prop.setValue(selVal);
                    properties.add(prop);
                };
                break;
            case XUnitReporter.polarionCustom:
                cfg = () -> {
                    Map<String, String> customFields = config.getXunit().getCustom().getProperties();
                    if (customFields.isEmpty())
                        return;
                    customFields.entrySet().forEach(entry -> {
                        String key = XUnitReporter.polarionCustom + "-" + entry.getKey();
                        String val = entry.getValue();
                        if (!val.equals("")) {
                            Property p = new Property();
                            p.setName(key);
                            p.setValue(val);
                            properties.add(p);
                        }
                    });
                };
                break;
            default:
                cfg = null;
        }
        return cfg;
    }

    private static Optional<com.github.redhatqe.polarizer.reporter.importer.testcase.Testcase> getTestcaseFromXML(File xmlDesc) {
        JAXBReporter jaxb = new JAXBReporter();
        Optional<com.github.redhatqe.polarizer.reporter.importer.testcase.Testcase> tc;
        tc = IJAXBHelper.unmarshaller(com.github.redhatqe.polarizer.reporter.importer.testcase.Testcase.class, xmlDesc,
                jaxb.getXSDFromResource(com.github.redhatqe.polarizer.reporter.importer.testcase.Testcase.class));
        if (!tc.isPresent())
            return Optional.empty();
        com.github.redhatqe.polarizer.reporter.importer.testcase.Testcase tcase = tc.get();
        return Optional.of(tcase);
    }

    private static Optional<Testsuites>
    getTestSuitesFromXML(File xmlDesc) {
        JAXBReporter jaxb = new JAXBReporter();
        Optional<Testsuites> ts;
        ts = IJAXBHelper.unmarshaller(Testsuites.class, xmlDesc,
                jaxb.getXSDFromResource(Testsuites.class));
        if (!ts.isPresent())
            return Optional.empty();
        Testsuites suites = ts.get();
        return Optional.of(suites);
    }

    private static Optional<Testsuite>
    getTestSuiteFromXML(File xmlDesc) {
        JAXBReporter jaxb = new JAXBReporter();
        Optional<Testsuite> ts;
        ts = IJAXBHelper.unmarshaller(Testsuite.class, xmlDesc,
                jaxb.getXSDFromResource(Testsuite.class));
        if (!ts.isPresent())
            return Optional.empty();
        Testsuite suites = ts.get();
        return Optional.of(suites);
    }

    private static String checkSelector(String selector) {
        // Make sure selector is in proper format
        if (!selector.contains("'")) {
            String[] tokens = selector.split("=");
            if (tokens.length != 2)
                throw new InvalidArgumentError("--selector must be in form of name=val");
            String name = tokens[0];
            String val = tokens[1];
            selector = String.format("%s='%s'", name, val);
            logger.info("Modified selector to " + selector);
        }
        return selector;
    }

    public static void main(String[] args) throws IOException {
        String xargs = args[0];
        XUnitConfig cfg = Serializer.from(XUnitConfig.class, new File(xargs));
        cfg.setCurrentXUnit(args[1]);
        Optional<File> maybeNew = XUnitReporter.createPolarionXunit(cfg);
    }
}
