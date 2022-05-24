(defproject com.github.redhatqe/polarizer-reporter "0.3.0-SNAPSHOT"
  :description "A small JMS/ActiveMQ helper library for Polarion"
  :url "https://github.com/RedHatQE/reporter"
  :java-source-path "src"
  :java-source-paths ["src"]
  :dependencies [[junit/junit "4.12"]
  		 [org.testng/testng "6.8.21"]
		 [com.fasterxml.jackson.core/jackson-core "2.9.2"]
		 [com.fasterxml.jackson.core/jackson-databind "2.9.2"]
		 [com.fasterxml.jackson.dataformat/jackson-dataformat-yaml "2.9.2"]
		 [org.apache.activemq/activemq-all "5.15.2"]
		 [org.apache.commons/commons-collections4 "4.1"]
 		 [org.slf4j/slf4j-simple "1.7.36"]
		 [com.github.redhatqe/polarize-metadata "0.1.1-SNAPSHOT"]
		 [com.sun.xml.bind/jaxb-impl "3.0.2"]
		 [com.sun.xml.bind/jaxb-core "3.0.2"]
 		 [javax.xml.bind/jaxb-api "2.3.1"]
		 ;;[jakarta.xml.bind/jakarta.xml.bind-api "3.0.1"]
		 ]
		 
  :javac-options {:debug "on"}
  :plugins [[lein2-eclipse "2.0.0"]])
