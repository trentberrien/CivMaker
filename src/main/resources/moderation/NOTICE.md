# Faction-name moderation data

`profanity-en.txt` is an unmodified copy of the English word list from
[LDNOOBW / List of Dirty, Naughty, Obscene, and Otherwise Bad Words](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words),
revision `5faf2ba42d7b1c0977169ec3611df25a3c08eb13`.

Copyright 2012–2020 Shutterstock, Inc. Licensed under
[Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/).
The license text is included in `LDNOOBW-LICENSE.txt`. No endorsement is implied.

CivMaker's `name-policy.json` is a separate application policy: it adds explicit
extremist references and common spelling variants, limits ambiguous short terms
to word boundaries, and exempts innocent words from substring matches. Neutral
dictionary entries such as "domination", "hardcore", and Roman-numeral / name
decorations are excluded from the default policy.

Names retain the existing ASCII-only identifier restriction. This is the most
restrictive identifier category described by [Unicode UTS #39](https://www.unicode.org/reports/tr39/).
Unicode lookalikes, formatting codes, combining marks, and invisible characters
are rejected by the character rule rather than being silently rewritten.

Matching is case-insensitive and checks separators, repeated letters, and common
digit substitutions, including ambiguous `1` as either `i` or `l`. Exceptions
cover only the offending match within the innocent word, never the entire name.
The filter is English-focused and supplemented by explicit group names; it is
not a universal contextual moderation service or a guarantee against every
possible evasion. Administrators can extend terms and word exceptions in config.

All resources are bundled in the plugin JAR; filtering makes no network calls.
