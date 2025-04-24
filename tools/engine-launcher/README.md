# engine

FIXME: description

## Installation

Download from http://example.com/FIXME.

## Usage

FIXME: explanation

    $ java -jar engine-0.1.0-standalone.jar [args]

## Options

FIXME: listing of options this app accepts.

## Examples

...

### Bugs

...

### Development
`lein test` to run tests
`lein uberjar` to build jar

To create reflect-config, from server/setup
```
java -agentlib:native-image-agent=config-output-dir=../../launcher-server/engine/target/native-image-config \
     -jar ../../launcher-server/engine/target/engine-0.1.0-SNAPSHOT-standalone.jar
```
Afterwards, copy `./target/native-image-config/reflect-config.json` to `./reflect-config.json`

`lein native-image` to compile to bin


## License

Copyright © 2025 FIXME

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
