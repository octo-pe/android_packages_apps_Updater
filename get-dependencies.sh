#!/bin/bash
mkdir -p dependencies
mvn dependency:copy -Dartifact=com.github.javadev:underscore:1.89 -DoutputDirectory=dependencies