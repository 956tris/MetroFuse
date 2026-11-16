"""Compare packaged Ktor/IO definitions with the host's resolved JVM libraries.

DEX method_ids also contains references to missing methods. Only definitions in
class_data count, so a plugin reference cannot make a stripped method pass.
Uses the standard library and checks both Android APKs and Windows release ZIPs.
"""

import argparse
from io import BytesIO
from pathlib import Path
import struct
import sys
import zipfile


PREFIXES = ('io/ktor/', 'kotlinx/io/')
VISIBLE = 0x0001 | 0x0004
STATIC = 0x0008


class Reader:
    def __init__(self, data, offset=0, endian='>'):
        self.data = data
        self.offset = offset
        self.endian = endian

    def number(self, size):
        value = struct.unpack_from(self.endian + {1: 'B', 2: 'H', 4: 'I'}[size], self.data, self.offset)[0]
        self.offset += size
        return value

    def uleb(self):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.number(1)
            value |= (byte & 0x7f) << shift
            if byte < 0x80:
                return value
        raise ValueError('Invalid DEX ULEB128')

    def attributes(self):
        for _ in range(self.number(2)):
            self.number(2)
            length = self.number(4)
            self.offset += length


def class_definition(data):
    reader = Reader(data)
    if reader.number(4) != 0xcafebabe:
        raise ValueError('Invalid JVM class')
    reader.number(2)
    reader.number(2)
    count = reader.number(2)
    pool = [None] * count
    index = 1
    while index < count:
        tag = reader.number(1)
        if tag == 1:
            length = reader.number(2)
            pool[index] = data[reader.offset:reader.offset + length].decode('utf-8', errors='replace')
            reader.offset += length
        elif tag == 7:
            pool[index] = reader.number(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            reader.offset += 4
        elif tag in (5, 6):
            reader.offset += 8
            index += 1
        elif tag in (8, 16, 19, 20):
            reader.offset += 2
        elif tag == 15:
            reader.offset += 3
        else:
            raise ValueError(f'Unknown JVM constant pool tag {tag}')
        index += 1
    reader.number(2)
    name = 'L' + pool[pool[reader.number(2)]] + ';'
    reader.number(2)
    interfaces = reader.number(2)
    reader.offset += 2 * interfaces
    definition = {'fields': {}, 'methods': {}}
    for kind in definition:
        for _ in range(reader.number(2)):
            flags = reader.number(2)
            member = pool[reader.number(2)]
            descriptor = pool[reader.number(2)]
            if flags & VISIBLE:
                definition[kind][(member, descriptor)] = flags
            reader.attributes()
    return name, definition


def jar_definitions(data):
    with zipfile.ZipFile(BytesIO(data)) as archive:
        return dict(
            class_definition(archive.read(name))
            for name in archive.namelist()
            if name.startswith(PREFIXES) and name.endswith('.class')
        )


def dex_definitions(data):
    if data[:4] != b'dex\n' or data[7] != 0 or struct.unpack_from('<I', data, 40)[0] != 0x12345678:
        raise ValueError('Invalid or unsupported DEX file')
    strings_size, strings_offset, types_size, types_offset = struct.unpack_from('<4I', data, 56)
    strings = []
    for index in range(strings_size):
        offset = struct.unpack_from('<I', data, strings_offset + 4 * index)[0]
        reader = Reader(data, offset, '<')
        reader.uleb()
        end = data.index(b'\0', reader.offset)
        strings.append(data[reader.offset:end].decode('utf-8', errors='replace'))
    # Android core-library desugaring relocates java.time/nio/util types to j$.
    # Compare their JVM identities while still checking actual method definitions.
    types = [
        strings[struct.unpack_from('<I', data, types_offset + 4 * index)[0]].replace('Lj$/', 'Ljava/')
        for index in range(types_size)
    ]
    _, protos_offset, _, fields_offset, _, methods_offset, classes_size, classes_offset = struct.unpack_from('<8I', data, 72)

    def method(index):
        _, proto, name = struct.unpack_from('<HHI', data, methods_offset + 8 * index)
        _, result, parameters = struct.unpack_from('<3I', data, protos_offset + 12 * proto)
        args = ''
        if parameters:
            count = struct.unpack_from('<I', data, parameters)[0]
            args = ''.join(types[struct.unpack_from('<H', data, parameters + 4 + 2 * item)[0]] for item in range(count))
        return strings[name], '(' + args + ')' + types[result]

    def field(index):
        _, value_type, name = struct.unpack_from('<HHI', data, fields_offset + 8 * index)
        return strings[name], types[value_type]

    definitions = {}
    for index in range(classes_size):
        values = struct.unpack_from('<8I', data, classes_offset + 32 * index)
        name = types[values[0]]
        if not name[1:-1].startswith(PREFIXES):
            continue
        definition = {'fields': {}, 'methods': {}}
        definitions[name] = definition
        if not values[6]:
            continue
        reader = Reader(data, values[6], '<')
        counts = [reader.uleb() for _ in range(4)]
        for group, count in enumerate(counts):
            member_index = 0
            for _ in range(count):
                member_index += reader.uleb()
                flags = reader.uleb()
                if group < 2:
                    definition['fields'][field(member_index)] = flags
                else:
                    reader.uleb()
                    definition['methods'][method(member_index)] = flags
    return definitions


def packaged_definitions(path):
    definitions = {}
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.endswith('.dex') and '/' not in name:
                definitions.update(dex_definitions(archive.read(name)))
            elif name.endswith('.jar'):
                definitions.update(jar_definitions(archive.read(name)))
    return definitions


def missing_api(expected, packaged):
    missing = []
    for name, definition in expected.items():
        actual = packaged.get(name)
        if actual is None:
            missing.append(name + ' (class)')
            continue
        for kind, members in definition.items():
            for (member, descriptor), flags in members.items():
                actual_flags = actual[kind].get((member, descriptor))
                if actual_flags is None or (actual_flags & STATIC) != (flags & STATIC):
                    missing.append(name + '->' + member + descriptor)
                elif flags & 0x0001 and not actual_flags & 0x0001:
                    missing.append(name + '->' + member + descriptor + ' (not public)')
    return sorted(missing)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    parser.add_argument('--classpath-file', required=True, type=Path)
    args = parser.parse_args()
    expected = {}
    for line in args.classpath_file.read_text(encoding='utf-8').splitlines():
        if line.strip():
            expected.update(jar_definitions(Path(line.strip()).read_bytes()))
    if not expected:
        raise ValueError('No resolved Ktor/IO ABI reference classes')
    packaged = packaged_definitions(args.package)
    missing = missing_api(expected, packaged)
    if missing:
        print(f'Release is missing {len(missing)} native-plugin API definitions:', file=sys.stderr)
        print('\n'.join(missing[:30]), file=sys.stderr)
        return 1
    members = sum(len(value['methods']) + len(value['fields']) for value in expected.values())
    print(f'Native-plugin ABI verified: {len(expected)} Ktor/IO classes, {members} public/protected methods and fields')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, IndexError, struct.error, zipfile.BadZipFile) as error:
        print(f'Native-plugin ABI verification failed: {error}', file=sys.stderr)
        sys.exit(1)
