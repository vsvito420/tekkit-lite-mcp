---
name: minecraft-bridge
description: Vitos Minecraft 1.4.7 (Lycodon-Client 3.1, Tekkit-artig) sehen und steuern – Position, Inventar, Blöcke, Chat, Screenshots, laufen, klicken, Kisten, und ComputerCraft-Computer lesen, bedienen und debuggen. Nutzen, wenn der Nutzer etwas "in Minecraft", "im Spiel", "auf Lycodon" erledigt, angeschaut oder geprüft haben will, oder einen ComputerCraft-Computer, Turtle oder ein Lua-Programm im Spiel erwähnt.
---

# Minecraft-Brücke (Claude Bridge)

Im Spiel läuft ein selbstgebauter LiteLoader-Mod, der auf `127.0.0.1:25599` eine Schnittstelle öffnet.
Bedient wird er über das Werkzeug `mc`:

```bash
<repo>/mc            # ohne Argumente: Befehlsliste
```

Voraussetzung: Minecraft läuft (Prism-Instanz `Lycodon-Client(1)`) und der Spieler steht in einer Welt.
Meldet `mc` "no token file" oder verweigert die Verbindung, läuft das Spiel nicht – den Nutzer bitten, es zu starten.

## Sehen

- `mc state` – Position, Blick, Inventar, offener Container (`container`), Variablen des Macro-Mods
  (`HEALTH`, `HUNGER`, `GUI`, `HITNAME`/`HITX`/`HITY`/`HITZ` = anvisierter Block, `ITEMNAME` …).
- `mc shot <datei>` – Screenshot als JPEG; danach mit Read ansehen. Immer dann, wenn Zahlen nicht reichen.
- `mc blocks [r] [ry]` – Block-IDs um den Spieler. `mc chat [seq]` – Chatzeilen.

## Welt lesen und bauen (ab Mod 0.3) – bevorzugt, kein Zielen per Kamera

Noch nicht im Spiel getestet (Stand 12.11.2026, wartet auf Neustart). Beim ersten Einsatz klein testen.

- `mc scan [r] [unten] [oben]` – alle Nicht-Luft-Blöcke um den Spieler als `x y z id[:meta]` (Standard 17×17×17).
  `mc area x1 y1 z1 x2 y2 z2 [list]` für einen beliebigen Kasten. Liest aus dem Speicher des Clients, auch verdeckte Blöcke.
- `mc place x y z [id[:meta]]` setzt einen Block. Für viele: Datei mit Zeilen `x y z [id[:meta]]`, dann
  `mc build datei [id]` (oder `-` für stdin), `mc wait`, danach `mc state` → `placed`, `skipped`, `failures`.
  Der Mod sortiert von unten nach oben, sucht selbst einen Nachbarblock zum Ansetzen und nimmt das Item aus der Hotbar.
  Reichweite ca. 4,5 Blöcke ab Auge: bei größeren Bauten mit `mc goto` nachrücken und den Rest erneut schicken
  (belegte Stellen werden übersprungen). Stellen im eigenen Körper werden abgelehnt.
- `mc dig x y z`, `mc use x y z [side]` (Kiste öffnen, Knopf), `mc select <slot>|id=<id>`, `mc look x y z`, `mc entities [r]`.
- `mc stop` leert auch die Bau-Warteschlange.

## Steuern

- `mc run '<script>'` führt ein Script des Macro/Keybind-Mods aus, z. B.
  `look(90,0); keydown(forward); wait(20t); keyup(forward)`, `key(use)` (Rechtsklick), `key(attack)`,
  `slot(3)`, `slotclick(12,l,false)`, `gui()` (Fenster schließen). Danach `mc wait`.
- `look(yaw,…)` zählt 0=Nord, 90=Ost, 180=Süd, 270=West. Das `yaw` aus `mc state` ist um 180° versetzt.
- `mc goto <x> <z>` läuft geradeaus dorthin (springt über einzelne Stufen, keine Wegfindung). Vorher mit
  `mc blocks` oder Screenshot prüfen, dass der Weg frei ist.
- `mc stop` bricht alles ab und lässt alle Tasten los.
- `mc say '<text>'` sendet Chat oder Befehle.

## ComputerCraft

Alle `mc cc …`-Befehle wirken auf den Computer oder Turtle, dessen Bildschirm im Spiel gerade offen ist.
Öffnen: Computer anvisieren (`HITNAME` in `mc state` prüfen), dann `mc run 'key(use)'`.

- `mc cc` – Bildschirm als Text, dazu Computer-ID, an/aus, Cursor.
- `mc cc type 'ls'` – tippt und drückt Enter. `mc cc write` ohne Enter. `mc cc key up 3` für Sondertasten.
- `mc cc terminate` beendet das laufende Programm (entspricht Strg+T halten), `mc cc reboot` (Strg+R),
  `mc cc shutdown`, `mc cc on`.
- **Dateien lesen und schreiben** (getestet 12.11.2026, 14 KB in 15 s lesen / 25 s schreiben, mit Prüfsumme):
  - `mc cc install` – einmal pro Computer: tippt den Helfer `cb` (Quelle `claude-bridge/cb.lua`) hinein und prüft ihn.
    Steht in `ls` schon `cb`, ist das nicht nötig.
  - `mc cc get <name> <lokale-datei>` lädt eine Datei vom Computer, `mc cc put <lokale-datei> <name>` schreibt sie zurück.
  - Beide brauchen den Shell-Prompt (`>`), also vorher `mc cc terminate`, falls ein Programm läuft.
  - Arbeitskopien liegen in `claude-bridge/cc-files/`. Vor dem Ändern immer frisch mit `get` holen – der Nutzer
    ändert Programme auch selbst im Spiel. Code lokal mit Read/Edit bearbeiten, dann `put`, dann testen.
  - Der Computer auf 1223/44/-2991 (Basis des Nutzers) ist ein Glücksspiel-Automat ("Crash Game", Programm `startup`,
    ältere Fassungen `v2`–`v4`, Zustand in `crash_state.dat`), an dem der Nutzer entwickelt.
- Auf dem Server sind `id` (zeigt -1) und `on` in der Ausgabe von `mc cc` nicht verlässlich; der Bildschirmtext stimmt.
- Im Spiel getestet: Bildschirm lesen, tippen, `terminate`, `reboot`, `edit` öffnen und ohne Speichern verlassen.
  Das Editor-Menü ist `[Save] Exit Print`: `ctrl`, dann `right`, dann `enter` verlässt ohne zu speichern.
- Nach `terminate` zum Schluss `mc cc reboot`, damit das Startprogramm des Computers wieder läuft – außer der
  Nutzer will es gestoppt lassen.

Debuggen: Läuft ein Programm und nimmt die Shell keine Eingaben an, zuerst `mc cc terminate`. Dann
die Datei mit `mc cc get` holen und lokal lesen (nicht im Editor blättern). Fehlermeldungen stehen nach
dem Programmstart auf dem Bildschirm (`mc cc`). Für kurze Tests: `mc cc type 'lua'`, dann Ausdrücke tippen,
mit `exit()` verlassen. Auf dem Server liegen die Dateien der Computer nicht lokal und nicht im Speicher des
Clients – dort ist nur der Bildschirm (51×19). Deshalb läuft der Transfer über `cb`.

## Regeln

- Lycodon ist ein öffentlicher Server mit der echten Basis des Nutzers. Nichts abbauen, wegwerfen,
  aus Kisten nehmen oder in den Chat schreiben, was nicht verlangt wurde.
- Nach dem Steuern Blickrichtung und offene Fenster so hinterlassen, wie sie waren, oder sagen, was anders ist.
- Wechselt der Nutzer aus dem Spiel, kann Minecraft ins Pausemenü gehen (`GUI` in `mc state` prüfen).

## Mod ändern

Quelle `claude-bridge/src/LiteModClaudeBridge.java`, bauen und installieren mit `claude-bridge/build.sh install`;
wirkt erst nach Neustart des Spiels. Die Klasse muss im Default-Package bleiben (dort liegen die
obfuskierten Minecraft-Klassen), und die Class-Dateien werden auf Java 7 gestempelt, weil Forge 1.4.7
neuere ablehnt. Obfuskierte Namen findet man per `javap -c` in `mods/mod-macros-0.9.8.litemod`.
