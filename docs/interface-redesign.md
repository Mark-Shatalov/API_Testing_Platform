# Interface redesign

## Reference and direction

The visual references are [Dotify](https://dotify.fun/en) and [Liner](https://tryliner.fun/).
Dotify contributes a quiet near-black canvas, strong typography and monochrome controls.
Liner contributes a narrow navigation rail, soft surfaces and a pale cyan action colour.
The result remains an API workbench: the URL, request options and actual response are its main content.

## Design tokens

- Canvas `#090b0e`
- Working surface `#11151a`
- Raised controls `#1b222a`
- Dividers `#29333d`
- Primary text `#f3f6f8`
- Default accent `#8edff3`

Geist is the interface typeface, with Segoe UI as the fallback. Consolas remains the code typeface.
The light palette uses a cool grey canvas, white working surfaces and the same geometric hierarchy.
Existing custom accent preferences remain valid.

## Layout

```text
 rail   API Testing Platform                         New request  Save  Workspace  Tools
        Workspace / Environment        [ Request tabs                                  ]
 Library Collections                   [ Method  URL                              Send ]
 History Search and saved requests     [ Request options                               ]
                                       [ --------------------------------------------- ]
 Settings                              [ Response and actual data                      ]
        Status and draft recovery
```

The rail separates application navigation from request commands. The collection tree stays beside
the editor. Only the working surface is framed; individual controls do not become separate cards.
All content is left-aligned. Rounded controls, restrained dividers and a larger type scale replace
the old dense toolbar and box outlines. Icons identify actions and item kinds, with text labels,
tooltips and keyboard focus retained.

## Review before implementation

A full-screen gradient and music-player cards would compete with request data. Use flat surfaces
for editing and reserve the visual emphasis for the primary action and the first-request prompt.
Do not use album artwork, promotional copy or decorative statistics. Keep named menus, saved data,
keyboard shortcuts, response checks and environment workflows intact.

## Verification

The full offline Maven install on 4 November 2026 passed all 271 tests with no failures,
errors or skips, including the optional workload with 10,000 saved requests and 500 local requests.
Both themes were visually inspected at the normal window size and at 1050 by 700.
Checks cover toolbar bounds, parameter row space, visible response instructions, bundled Geist
registration and the existing request, authentication, extraction, history and recovery workflows.

The three unmodified static font faces and their SIL Open Font License are present in the built
UI JAR. Font loading and icons require no runtime network connection. The stylesheet contains
one set of scoped control rules; no external styles, icon fonts or animation libraries are loaded.
