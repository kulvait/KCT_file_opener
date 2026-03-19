# KCT File Opener

## Motivation

Due to the need for **compressed multidimensional formats** in the KCT project, the existing DEN format will gradually be supplemented by **Zarr**.

We needed a **reliable plugin** to quickly visualize these files without being burdened by unnecessary features.

This plugin is similar to [KCT DEN File Opener](https://github.com/kulvait/KCT_den_file_opener) but relies on the **heavy `java-zarr` library**, so it was developed independently to handle Zarr files effectively.

The architecture mirrors the original DEN file opener: it leverages [ImageJ’s Raw File Opener](https://imagej.nih.gov/ij/plugins/raw-file-opener.html) logic, using a pluggable backend for reading data.

Its primary goal is to open **on-disk Zarr files** and [DEN files](https://kulvait.github.io/KCT_doc/den-format.html) used in the KCT project. While it may not implement the full Zarr specification, it provides a practical solution that works better for most use cases than other existing approaches. It is not necessary to install DEN file opener to open DEN files, as this plugin can handle both formats. When you do not need Zarr support, you can use the DEN file opener which is more lightweight and does not require array of dependencies.

How to use this plugin
======================

You have two main options:

**Clone from GitHub and build yourself** (recommended if you want the latest development version):
```bash
git clone git@github.com:kulvait/KCT_file_opener.git
cd KCT_zar_file_opener
```

Install maven and Java 17, then for non shaded version run:

```bash
mvn clean package -U
mvn dependency:copy-dependencies -DoutputDirectory=dependency-jars -DincludeScope=runtime -U
```

When there are problems with enforcer plugin, run:

```bash
mvn clean package -Denforcer.skip=true
```

For fat jar with all dependencies included run:

```bash
mvn clean package -Pfat-jar
```

**Download a pre-built release JAR from the GitHub Releases page** (recommended for most users):

Copy jar file from target directory to ~/.imagej/plugins and restart ImageJ. And if it is slim version, also copy all dependency jars from dependency-jars to ~/.imagej/jars.

Code formating
```bash
mvn spotless:apply
```

Java Runtime
------------

Some clusters might not have a Java runtime installed.  
This project was **built and tested with Java 17 (Temurin)**:

Download via [Adoptium](https://adoptium.net/temurin/releases/?version=17) or via package manager:

```bash
apt-get install temurin-17-jre
```

Tested with [Java 17 temurin](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.9%2B9/OpenJDK17U-debugimage_x64_linux_hotspot_17.0.9_9.tar.gz).

## Drag and drop

I have developped a simple drag and drop via providing custom HandleExtraFileTypes.

It might conflict with other plugins providing the same functionality, namely in recent Fiji update it conflicted with IO plugin. As the plugin priority is by name alphabetically, you can rename the jar file to something starting with A to have it loaded first or rename IO plugin to something starting with Z to have it loaded last.

To find conflicting plugins just search for "Find Jar For Class" and then type "HandleExtraFileTypes" to see all plugins providing this functionality. There might be other ways how to provide drag and drop functionality, e.g. via SCIFIO, but I have not explored them yet, see [this discussion](https://imagej.nih.narkive.com/QUoWdvgX/handle-extra-files-types-not-working).

## Dependencies

Dependencies may have their own licenses, namely [zarr-java](https://github.com/zarr-developers/zarr-java) is licensed under MIT License.

## Licensing

When there is no other licensing and/or copyright information in the source files of this project, the following apply for the source files:

Copyright (C) 2026 Vojtěch Kulvait

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, version 3 of the License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
