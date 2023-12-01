#!/bin/bash

export MAVEN_OPTS=-Xmx2g

./build/mvn -pl core -Pyarn package -DskipTests
cp core/target/spark-core_2.12-3.1.2.jar assembly/target/scala-2.12/jars/
