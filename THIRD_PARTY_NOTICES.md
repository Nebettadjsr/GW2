# Third-party notices

## Cinzel display font

The Home header uses Cinzel SemiBold, copyright 2020 The Cinzel Project Authors
(<https://github.com/NDISCOVER/Cinzel>), distributed through Google Fonts under the
SIL Open Font License, version 1.1. The font is hosted locally in
`frontend/src/home/fonts/cinzel-semibold.ttf`; the complete copyright notice and license
are included in `frontend/public/fonts/cinzel-OFL.txt` and served at `/fonts/cinzel-OFL.txt`.

## Handlee handwritten font

The Home origin-story letter uses Handlee Regular, copyright 2011 Joe Prince, Vissol Ltd.,
with Reserved Font Name "Handlee" (<https://github.com/googlefonts/handlee>), distributed through Google Fonts under the
SIL Open Font License, version 1.1. The font is hosted locally in
`frontend/src/home/fonts/handlee-regular.woff2`; the complete copyright notice and license
are included in `frontend/public/fonts/handlee-OFL.txt` and served at `/fonts/handlee-OFL.txt`.

## Mystic Forge recipe catalog

`backend/src/main/resources/mystic-forge/catalog.json` is derived from the Guild Wars 2 Wiki
(<https://wiki.guildwars2.com/>, hosted by ArenaNet): Semantic MediaWiki recipe rows and the `{{recipe}}` templates of
their parent pages, captured under `backend/tools/mystic-forge/source/` (hashes in `verified-facts.json`). Wiki text and
data is contributed by GW2 Wiki contributors and licensed under the **GNU Free Documentation License, version 1.3**
(<https://wiki.guildwars2.com/wiki/Guild_Wars_2_Wiki:Copyrights>); the derived catalog and the captured source files
remain under that license. Guild Wars 2 names, item identifiers and game assets remain the property of ArenaNet/NCSOFT.
The application's own code stays under the MIT license in `LICENSE`; the two are separate.

The one canonical, complete notice (this attribution plus the full GFDL 1.3 text) is distributed with the data it
covers, in the application resource
[`backend/src/main/resources/mystic-forge/NOTICE.md`](backend/src/main/resources/mystic-forge/NOTICE.md), next to
`catalog.json`. It is intentionally not duplicated here.
