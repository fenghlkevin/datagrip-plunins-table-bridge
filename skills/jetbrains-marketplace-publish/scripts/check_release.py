#!/usr/bin/env python3
"""Offline inspection of a JetBrains plugin archive; never uploads or extracts files."""
import argparse
import hashlib
import io
import json
import sys
import zipfile
import xml.etree.ElementTree as ET


def sha(data):
    return hashlib.sha256(data).hexdigest()


def inspect(path, expected_id=None, expected_version=None):
    result = {'artifact': str(path), 'errors': [], 'warnings': [], 'logos': {}}
    errors = result['errors']
    try:
        with open(path, 'rb') as handle:
            data = handle.read()
        result['sha256'] = sha(data)
        with zipfile.ZipFile(io.BytesIO(data)) as outer:
            candidates = []
            if 'META-INF/plugin.xml' in outer.namelist():
                candidates.append(('archive', data))
            for name in outer.namelist():
                if name.endswith('.jar'):
                    raw = outer.read(name)
                    with zipfile.ZipFile(io.BytesIO(raw)) as nested:
                        if 'META-INF/plugin.xml' in nested.namelist():
                            candidates.append((name, raw))
        if len(candidates) != 1:
            raise ValueError('Expected exactly one main JAR descriptor; found ' + str(len(candidates)))
        result['mainJar'] = candidates[0][0]
        with zipfile.ZipFile(io.BytesIO(candidates[0][1])) as jar:
            root = ET.fromstring(jar.read('META-INF/plugin.xml'))
            for tag in ('id', 'name', 'version', 'vendor'):
                value = (root.findtext(tag) or '').strip()
                result[tag] = value
                if not value:
                    errors.append('Missing descriptor field: ' + tag)
            for tag, expected in [('id', expected_id), ('version', expected_version)]:
                if expected and result[tag] != expected:
                    errors.append(tag + ' does not match expected value')
            version = root.find('idea-version')
            result['compatibility'] = dict(version.attrib) if version is not None else {}
            for name in ('pluginIcon.svg', 'pluginIcon_dark.svg'):
                location = 'META-INF/' + name
                if location not in jar.namelist():
                    if name == 'pluginIcon.svg':
                        errors.append('Missing required main JAR logo: ' + location)
                    continue
                raw = jar.read(location)
                result['logos'][location] = {'sha256': sha(raw), 'bytes': len(raw)}
                svg = ET.fromstring(raw)
                if svg.tag.split('}')[-1] != 'svg':
                    errors.append(location + ': root must be svg')
                def dimension(value):
                    return float(value.removesuffix('px'))
                try:
                    if dimension(svg.get('width', '')) != 40 or dimension(svg.get('height', '')) != 40:
                        errors.append(location + ': width and height must be 40')
                except ValueError:
                    errors.append(location + ': explicit 40px dimensions required')
                if svg.get('viewBox'):
                    try:
                        if list(map(float, svg.get('viewBox').replace(',', ' ').split())) != [0, 0, 40, 40]:
                            errors.append(location + ': use viewBox 0 0 40 40')
                    except ValueError:
                        errors.append(location + ': invalid viewBox')
                for node in svg.iter():
                    if node.tag.split('}')[-1] in ('image', 'script', 'foreignObject'):
                        errors.append(location + ': requires plain vector artwork, no bitmap/script/foreignObject')
                    for key, value in node.attrib.items():
                        if key.split('}')[-1] == 'href' and not value.startswith('#'):
                            errors.append(location + ': external or embedded resource reference')
                if len(raw) > 3072:
                    result['warnings'].append(location + ': above suggested 2–3KB; review size')
    except (OSError, ValueError, zipfile.BadZipFile, ET.ParseError, KeyError) as exc:
        errors.append(str(exc))
    result['ok'] = not errors
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact')
    parser.add_argument('--expected-id')
    parser.add_argument('--expected-version')
    args = parser.parse_args()
    report = inspect(args.artifact, args.expected_id, args.expected_version)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    sys.exit(0 if report['ok'] else 1)
