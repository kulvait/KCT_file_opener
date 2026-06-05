# KCT File Opener

ImageJ/Fiji plugin for opening Zarr and DEN files. Using [JEP](https://github.com/ninia/jep), any Zarr array encoded with a codec supported by Python's [imagecodecs](https://github.com/cgohlke/imagecodecs) or custom cedecs in Python can be opened in ImageJ without the need for Java implementations of these codecs. This plugin is designed to be a practical solution for opening on-disk Zarr files and DEN files used in the KCT project, providing better performance and reliability than other existing approaches.

## Motivation

This plugin is similar to [KCT DEN File Opener](https://github.com/kulvait/KCT_den_file_opener) but relies on the [zarr-java](https://github.com/kulvait/zarr-java) library. Due to number of recursive dependencies, it was developed independently of original [KCT DEN File Opener](https://github.com/kulvait/KCT_den_file_opener) as its version supporting Zarr for the price of more complex build and setup.

Library [zarr-java](https://github.com/zarr-developers/zarr-java) had slow implementation of on disk Zip reading, which was fixed in the fork [zarr-java](https://github.com/kulvait/zarr-java). It is fast Java native implementation of Zarr format, but it lacks support for custom codecs such as those in Python package [imagecodecs](https://github.com/cgohlke/imagecodecs). Therefore, this plugin uses JEP to leverage Python's imagecodecs for decoding Zarr arrays, providing a practical solution that works better for most use cases than other existing approaches.


The architecture mirrors the original DEN file opener: it leverages [ImageJ’s Raw File Opener](https://imagej.nih.gov/ij/plugins/raw-file-opener.html) logic, using a pluggable backend for reading data.

Its primary goal is to open **on-disk Zarr files** and [DEN files](https://kulvait.github.io/KCT_doc/den-format.html) used in the KCT project. Two key capabilities distinguish this plugin:

- **Arbitrary imagecodecs codec support** — via Python bindings ([JEP](https://github.com/ninia/jep) bridge to CPython), any Zarr array encoded with a codec supported by Python's [imagecodecs](https://github.com/cgohlke/imagecodecs) library can be opened, without requiring a native Java implementation of that codec.
- **Good performance on zipped Zarr** — the underlying [zarr-java](https://github.com/kulvait/zarr-java) library uses fast ZIP index-based access, avoiding full decompression of the archive when reading individual chunks.

## Supported Formats

| Format | Extension | Plugin menu entry | Description |
|--------|-----------|-------------------|-------------|
| [DEN](https://kulvait.github.io/KCT_doc/den-format.html) | `.den` | File → Open DEN ... | KCT native raw binary format, little-endian, multi-type |
| Zarr | directory or `.zip` | File → Open ZAR ... | Zarr v2/v3 stores, on-disk directory or zipped |

All formats are accessible via **drag and drop** (see below).

Saving back to DEN is also supported via **File → Save DEN ...**.

## Architecture

### Zarr reading pipeline

Array reading follows a two-stage fallback strategy:

1. **zarr-java** (pure Java) — the primary path. If the codec used by the Zarr array is supported natively by zarr-java, data is decoded entirely in Java. For zipped Zarr stores, zarr-java uses fast ZIP index-based chunk access, giving good read performance without extracting the full archive.
2. **JEP Bridge** (Python fallback) — if zarr-java can parse the array metadata (`LenientMetadata.ArrayInfo`) but cannot decode the data (e.g., the codec is provided by Python's [imagecodecs](https://github.com/cgohlke/imagecodecs) library), the `JEPBridge` is invoked. It calls CPython via [JEP](https://github.com/ninia/jep) in a dedicated single worker thread and returns the decoded slice as raw bytes to Java. This makes it possible to open Zarr arrays compressed with **any codec supported by imagecodecs** — including `zfp`, `blosc2`, `lz4`, `jpeg2000`, `bz2`, `lzma`, and many more — without requiring a native Java codec implementation.

### Key classes

| Class | Description |
|-------|-------------|
| `ZarFileOpener` | ImageJ `PlugIn` entry point for Zarr/ZAR files |
| `DenFileOpener` | ImageJ `PlugIn` entry point for DEN files |
| `DatFileOpener` | ImageJ `PlugIn` entry point for DAT files |
| `ZarFileInfo` | Detects whether a path is a valid Zarr store (directory or ZIP) |
| `ZarrFactory` | Opens a Zarr store handle; builds the node tree; owns the static `JEPBridge` instance |
| `ZarrNode` / `ZarrArrayNode` / `ZarrGroupNode` | In-memory tree model of the Zarr hierarchy |
| `ZarOpenerAccessory` | JFileChooser panel showing a live JTree preview of the Zarr array hierarchy |
| `JEPBridge` | Singleton bridge to CPython via JEP; all Python calls run on a single dedicated daemon thread |

## JEP Bridge — Python Fallback for imagecodecs

### When is it used?

The JEP bridge activates automatically when zarr-java encounters an array whose codec it cannot decode natively. No manual configuration is required to try it; however, **a Python environment must be prepared in advance**.

Thanks to the JEP bridge, this plugin can open **any Zarr array whose codec is supported by [imagecodecs](https://github.com/cgohlke/imagecodecs)**, which covers a very wide range of scientific and general-purpose compression formats.

### Setting up the Python environment

To use the JEP bridge, you need to have Python installed with the `imagecodecs` library. You can set up a Python environment as follows:
```bash
export ENV=/<PATH_TO_ENV>/minizarr
export FIJI_HOME=~/.imagej
export JAVA_HOME=<PATH_TO_TREMULIN>/jdk-21
export PATH=$JAVA_HOME/bin:$ENV/bin:$PATH
export LD_LIBRARY_PATH=$ENV/lib:$LD_LIBRARY_PATH
module load mamba
mamba create --prefix $ENV --no-default-packages --copy
mamba activate $ENV
mamba install -c conda-forge setuptools numpy imagecodecs zarr

git clone https://github.com/ninia/jep/

cd jep
python setup.py build
python setup.py install

cp jep/build/java/jep-4.3.1.jar $FIJI_HOME/jars
```

## Compiling KCT File Opener using maven

**Clone from GitHub and build yourself** (recommended if you want the latest development version):
```bash
git clone git@github.com:kulvait/KCT_file_opener.git
cd KCT_file_opener
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

Copy jar file from target directory to ~/.imagej/plugins and jars from dependency-jars to ~/.imagej/jars and restart ImageJ.


For fat jar with all dependencies included run, not tested yet due to array of depencencies of `zarr-java`:

```bash
mvn clean package -Pfat-jar
```

Code formating
```bash
mvn spotless:apply
```

Java Runtime
------------

Some clusters might not have a Java runtime installed.  
This project was **built and tested with Java 17 (Temurin)**:

Download via [Adoptium](https://adoptium.net/temurin/releases/?version=21) or via package manager:

```bash
apt-get install temurin-21-jre
```

It shall work with [Java 17 temurin](https://github.com/adoptium/temurin17-binaries) or [Java 21 temurin](https://github.com/adoptium/temurin21-binaries), but it is not guaranteed to work with older Java versions. 

## FiJi integration

Depending on the setup it might be necessery to update `LD_LIBRARY_PATH` to include lib path of Python environment, where JEP is installed and `JAVA_HOME` against which it was built. For example we can create a script to run Fiji with the correct environment variables set:

```bash
export ENV=/<PATH_TO_ENV>/minizarr
export FIJI_HOME=<PATH_TO_FIJI>
export JAVA_HOME=<PATH_TO_TREMULIN>/jdk-21
export PATH=$JAVA_HOME/bin:$ENV/bin:$PATH
export LD_LIBRARY_PATH=$ENV/lib:$LD_LIBRARY_PATH
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$SCRIPT_DIR/fiji-linux-x64" \
    -- \
    -Dkct.python.env="$ENV" 

```
## Drag and drop

A custom `HandleExtraFileTypes` implementation provides drag-and-drop support for all three formats.

It might conflict with other plugins providing the same functionality — notably, in recent Fiji updates it has conflicted with the IO plugin. Because plugin priority is determined alphabetically by name, you can rename the JAR file to change its precedence.

To find conflicting plugins, use **Plugins → Utilities → Find Jar For Class** and search for `HandleExtraFileTypes` to see all plugins providing this functionality. In the future, I may explore alternative approaches to providing drag-and-drop support, such as via SCIFIO, but I have not yet investigated those options (see [this discussion](https://imagej.nih.narkive.com/QUoWdvgX/handle-extra-files-types-not-working)). I also opened issue to improve [drag-and-drop support in ImageJ](https://github.com/fiji/fiji/issues/428).


## Dependencies

Dependencies may have their own licenses:

- [zarr-java (kulvait fork)](https://github.com/kulvait/zarr-java) — MIT License
- [JEP (Java Embedded Python)](https://github.com/ninia/jep) — zlib License
- [ImageJ](https://imagej.net/) — Public Domain / BSD

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
