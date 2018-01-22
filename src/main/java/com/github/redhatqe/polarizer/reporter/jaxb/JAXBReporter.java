package com.github.redhatqe.polarizer.reporter.jaxb;



import com.github.redhatqe.polarizer.reporter.importer.testcase.Testcases;
import com.github.redhatqe.polarizer.reporter.importer.xunit.Testcase;
import com.github.redhatqe.polarizer.reporter.importer.xunit.Testsuite;
import com.github.redhatqe.polarizer.reporter.importer.xunit.Testsuites;

import java.net.URL;

/**
 * Created by stoner on 8/16/16.
 */
public class JAXBReporter implements IJAXBHelper {
    @Override
    public URL getXSDFromResource(Class<?> t) {
        URL xsd;
        if (t == Testcase.class || t == Testsuite.class || t == Testsuites.class) {
            xsd = JAXBReporter.class.getClassLoader().getResource("xunit_importers/xunit.xsd");
        }
        else if (t == com.github.redhatqe.polarizer.reporter.importer.testcase.Testcase.class) {
            xsd = JAXBReporter.class.getClassLoader().getResource("testcase_importer/testcase-importer.xsd");
        }
        else if (t == Testcases.class) {
            xsd = JAXBReporter.class.getClassLoader().getResource("testcase_importer/testcase-importer.xsd");
        }
        else
            throw new XSDValidationError(String.format("Could not find schema for class %s", t.getName()));
        return xsd;
    }
}
