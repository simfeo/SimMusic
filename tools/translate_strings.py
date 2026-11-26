"""Fill in whatever the locale files are missing, translating through DeepL.

    python tools/translate_strings.py [--dry-run] [--key <path to deepl_api_key.txt>]

Only keys absent from a locale are sent, so re-running it costs nothing and never
overwrites a translation that was corrected by hand. Keys a locale carries that the
default file no longer has are reported, not deleted, since a stale key is cheap and a
wrongly deleted one is not.

Two things have to survive the round trip and neither is DeepL's default behaviour:

- Format placeholders such as %1$d. They are wrapped in a tag DeepL is told to leave
  alone, because a translated "%1 $d" or a reordered pair crashes String.format at
  runtime rather than merely reading badly.
- Apostrophes, which Android requires escaped inside a <string>. French and Italian
  produce them constantly, and an unescaped one fails the resource compiler.
"""
import argparse
import glob
import io
import os
import re
import sys
import time

# No default key location; pass --key or set DEEPL_KEY_FILE.
DEFAULT_KEY = os.environ.get('DEEPL_KEY_FILE', '')

# Android locale directory -> DeepL target. Portuguese and Chinese need the regional
# form; DeepL rejects a bare "PT" and warns on a bare "ZH".
TARGETS = {
    'values-de': 'DE',
    'values-es': 'ES',
    'values-fr': 'FR',
    'values-it': 'IT',
    'values-ja': 'JA',
    'values-ko': 'KO',
    'values-pt': 'PT-PT',
    'values-uk': 'UK',
    'values-zh': 'ZH-HANS',
}

# Product name; it is also marked translatable="false".
SKIP = {'app_name'}

BASE = os.path.join('app', 'src', 'main', 'res', 'values', 'strings.xml')
RES = os.path.join('app', 'src', 'main', 'res')

PLACEHOLDER = re.compile(r'%(?:\d+\$)?[sdfx]|%%')


def entries(path):
    """Ordered (key, raw value) pairs, raw meaning still Android-escaped.

    Anything marked translatable="false" is dropped here rather than at the call site,
    so a locale is never asked to carry a key the default file says to leave alone.
    """
    text = io.open(path, encoding='utf-8').read()
    out = []
    for attrs, value in re.findall(r'<string ([^>]*)>(.*?)</string>', text, re.S):
        if 'translatable="false"' in attrs:
            continue
        name = re.search(r'name="([^"]+)"', attrs)
        if name:
            out.append((name.group(1), value))
    return out


def unescape(s):
    return s.replace("\\'", "'").replace('\\"', '"').replace('&amp;', '&')


def escape(s):
    return s.replace('&', '&amp;').replace("'", "\\'")


def protect(s):
    """Wrap placeholders so DeepL copies them through untouched."""
    return PLACEHOLDER.sub(lambda m: '<ph>%s</ph>' % m.group(0), s)


def unprotect(s):
    return from_xml(re.sub(r'</?ph>', '', s))


def from_xml(s):
    """Undo the XML escaping DeepL applies to its own output.

    The placeholders are protected with a <ph> tag, which means asking for
    tag_handling="xml", and in that mode DeepL escapes the text it returns. A ">" in a
    translation therefore arrives as "&gt;", and escape() below would turn its ampersand
    into "&amp;gt;", which Android then draws on screen entity and all. "&amp;" is
    decoded last, so an ampersand the translator really wrote does not swallow whatever
    follows it.
    """
    for entity, ch in (('&lt;', '<'), ('&gt;', '>'), ('&quot;', '"'), ('&#39;', "'")):
        s = s.replace(entity, ch)
    return s.replace('&amp;', '&')


# An entity whose ampersand has itself been escaped: the value would read as literal
# "&gt;" rather than ">". Always a bug in this script, never something a translator meant.
DOUBLE_ESCAPED = re.compile(r'&amp;(?:lt|gt|amp|quot|apos|#\d+);')


def assert_not_double_escaped(value, key, locale):
    if DOUBLE_ESCAPED.search(value):
        raise SystemExit(
            '%s/%s would be written double escaped, which shows the entity to the '
            'user: %s' % (locale, key, value))


def translate(client, texts, target):
    """One request for the whole locale, retried through DeepL's load shedding.

    The server returns 429 under load rather than queueing, and a locale is worthless
    half translated, so this backs off and keeps the batch together.
    """
    import deepl
    delay = 5
    for attempt in range(6):
        try:
            return client.translate_text(texts, target_lang=target,
                                         tag_handling='xml', ignore_tags=['ph'])
        except deepl.exceptions.TooManyRequestsException:
            print('  busy, retrying in %ds' % delay, flush=True)
            time.sleep(delay)
            delay *= 2
    raise SystemExit('DeepL stayed busy for %s, try again later' % target)


def check(src, out, key, locale):
    """Every placeholder in the English must come back, in the same multiset."""
    want = sorted(PLACEHOLDER.findall(src))
    got = sorted(PLACEHOLDER.findall(out))
    if want != got:
        print('  WARNING %s/%s placeholders changed: %s -> %s'
              % (locale, key, want, got))
        return False
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true',
                    help='report what is missing and translate nothing')
    ap.add_argument('--key', default=DEFAULT_KEY)
    args = ap.parse_args()

    base = entries(BASE)
    base_keys = [k for k, _ in base]
    base_map = dict(base)

    work = {}
    for path in sorted(glob.glob(os.path.join(RES, 'values-*', 'strings.xml'))):
        locale = os.path.basename(os.path.dirname(path))
        if locale not in TARGETS:
            continue
        have = {k for k, _ in entries(path)}
        missing = [k for k in base_keys if k not in have and k not in SKIP]
        stale = sorted(have - set(base_keys))
        if stale:
            print('%-12s %d key(s) not in the default file: %s'
                  % (locale, len(stale), ', '.join(stale)))
        if missing:
            work[locale] = missing
        print('%-12s %d to translate' % (locale, len(missing)))

    if args.dry_run or not work:
        print('nothing written')
        return 0

    import deepl
    with io.open(args.key, encoding='utf-8') as fh:
        client = deepl.DeepLClient(fh.readline().strip())

    for locale, missing in sorted(work.items()):
        path = os.path.join(RES, locale, 'strings.xml')
        text = io.open(path, encoding='utf-8').read()
        print('%s: translating %d' % (locale, len(missing)))

        english = [unescape(base_map[k]) for k in missing]
        results = translate(client, [protect(s) for s in english], TARGETS[locale])

        for key, src, result in zip(missing, english, results):
            value = unprotect(result.text)
            check(src, value, key, locale)

            # Placed after whichever earlier key this locale already has, so related
            # strings stay together instead of all piling up at the end of the file.
            anchor = None
            for prev in reversed(base_keys[:base_keys.index(key)]):
                if 'name="%s"' % prev in text:
                    anchor = prev
                    break
            escaped = escape(value)
            assert_not_double_escaped(escaped, key, locale)
            line = '    <string name="%s">%s</string>\n' % (key, escaped)
            if anchor is None:
                text = text.replace('<resources>\n', '<resources>\n' + line, 1)
            else:
                m = re.search(r'[ \t]*<string name="%s">.*?</string>\r?\n' % anchor,
                              text, re.S)
                text = text[:m.end()] + line + text[m.end():]

        with io.open(path, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(text)
        print('%s: written' % locale)
    return 0


if __name__ == '__main__':
    sys.exit(main())
