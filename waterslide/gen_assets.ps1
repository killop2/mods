# Generates textures, blockstates and models for the waterslide mod. ASCII only (PowerShell 5 reads scripts as ANSI).
Add-Type -AssemblyName System.Drawing
$root = "D:\mods\waterslide\src\main\resources"
$a = "$root\assets\waterslide"
foreach ($d in "textures\block","textures\item","textures\entity","models\block","models\item","blockstates") { New-Item -ItemType Directory -Force "$a\$d" | Out-Null }

function Col([int]$r,[int]$g,[int]$b,[int]$al=255) { [System.Drawing.Color]::FromArgb($al,[Math]::Max(0,[Math]::Min(255,$r)),[Math]::Max(0,[Math]::Min(255,$g)),[Math]::Max(0,[Math]::Min(255,$b))) }
function Mix($c1,$c2,[double]$t) { Col ([int]($c1[0]+($c2[0]-$c1[0])*$t)) ([int]($c1[1]+($c2[1]-$c1[1])*$t)) ([int]($c1[2]+($c2[2]-$c1[2])*$t)) }
function Save($bmp, $path) { $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose() }
$rnd = New-Object System.Random 7

# ---------------- slide.png (64x16): inner | outer | rib | metal | concrete | deck
$b = New-Object System.Drawing.Bitmap 64,16
for ($y=0;$y -lt 16;$y++) {
  # trough columns are NEUTRAL (grey/white): each slide type tints them with its own vertex colour
  for ($x=0;$x -lt 16;$x++) {           # inner fibreglass: a bit darker at the bottom, glossy walls, two highlight streaks
    $t = [Math]::Abs(($x + 0.5)/16 - 0.5) * 2
    $c = Mix @(222,222,222) @(255,255,255) $t
    if ($x -eq 4 -or $x -eq 11) { $c = Col 255 255 255 }
    $b.SetPixel($x,$y,$c) }
  for ($x=0;$x -lt 16;$x++) {           # outer shell
    $t = [Math]::Abs(($x + 0.5)/16 - 0.5) * 2
    $b.SetPixel(16+$x,$y,(Mix @(182,182,182) @(238,238,238) $t)) }
  for ($x=0;$x -lt 8;$x++) { $b.SetPixel(32+$x,$y,(Col 214 214 214)) }       # ribs and lips
  for ($x=0;$x -lt 8;$x++) {            # galvanised steel
    $t = [Math]::Abs(($x + 0.5)/8 - 0.5) * 2
    $b.SetPixel(40+$x,$y,(Mix @(232,236,239) @(150,158,166) $t)) }
  for ($x=0;$x -lt 8;$x++) { $n = $rnd.Next(-14,14); $b.SetPixel(48+$x,$y,(Col (162+$n) (166+$n) (156+$n))) }   # concrete
  for ($x=0;$x -lt 8;$x++) {            # deck grating
    $c = if (($y % 3) -eq 0 -or ($x % 4) -eq 0) { Col 88 94 101 } else { Col 128 135 142 }
    $b.SetPixel(56+$x,$y,$c) }
}
Save $b "$a\textures\entity\slide.png"

# ---------------- slide_water.png (64x16): 4 frames, streaks move down by 4 px per frame
$streaks = @(@(2,1,5),@(6,9,4),@(10,4,6),@(13,12,3),@(4,13,3),@(8,0,3))
$w = New-Object System.Drawing.Bitmap 64,16
for ($f=0;$f -lt 4;$f++) {
  for ($y=0;$y -lt 16;$y++) { for ($x=0;$x -lt 16;$x++) {
    $edge = [Math]::Abs(($x + 0.5)/16 - 0.5) * 2
    $al = [int](150 - 70*$edge*$edge)
    $w.SetPixel($f*16+$x,$y,(Col 120 205 255 $al)) } }
  foreach ($s in $streaks) {
    for ($k=0;$k -lt $s[2];$k++) {
      $yy = ($s[1] + $k + $f*4) % 16
      $w.SetPixel($f*16+$s[0],$yy,(Col 235 250 255 205)) } }
}
Save $w "$a\textures\entity\slide_water.png"

# ---------------- particle texture (break particles)
$p = New-Object System.Drawing.Bitmap 16,16
for ($y=0;$y -lt 16;$y++) { for ($x=0;$x -lt 16;$x++) { $n = $rnd.Next(-12,12); $p.SetPixel($x,$y,(Col (245+$n) (190+$n) (20+$n))) } }
Save $p "$a\textures\block\particle.png"

# ---------------- item icons, one per slide type
function Icon($name, $kind, $c1, $c2) {
  $i = New-Object System.Drawing.Bitmap 16,16
  $g = [System.Drawing.Graphics]::FromImage($i)
  $g.SmoothingMode = "None"
  $pen = New-Object System.Drawing.Pen $c1, 2
  $pen2 = New-Object System.Drawing.Pen $c2, 2
  $g.FillRectangle((New-Object System.Drawing.SolidBrush (Col 140 146 152)), 0, 1, 5, 2)          # start platform
  switch ($kind) {
    "classic" {
      $g.FillRectangle((New-Object System.Drawing.SolidBrush (Col 170 176 182)), 7, 3, 2, 12)
      $g.DrawArc($pen, 1, 2, 14, 5, 200, 320); $g.DrawArc($pen2, 1, 6, 14, 5, 20, 320); $g.DrawArc($pen, 1, 10, 14, 4, 200, 300) }
    "drop" {
      $g.DrawLine($pen, 3, 3, 7, 9); $g.DrawLine($pen2, 7, 9, 10, 11); $g.DrawArc($pen, 8, 8, 6, 6, 180, 300); $g.DrawLine($pen2, 2, 14, 9, 13) }
    "spiral" {
      $g.DrawLine($pen, 3, 3, 6, 4); $g.DrawArc($pen, 3, 3, 10, 4, 0, 330); $g.DrawArc($pen2, 3, 7, 10, 4, 0, 330); $g.DrawArc($pen, 3, 11, 10, 4, 0, 250) }
    "serpentine" {
      $g.DrawArc($pen, 2, 3, 8, 5, 180, 180); $g.DrawArc($pen2, 6, 6, 8, 5, 0, 180); $g.DrawArc($pen, 2, 9, 8, 5, 180, 180); $g.DrawLine($pen2, 2, 14, 8, 14) }
  }
  $g.Dispose()
  Save $i "$a\textures\item\$name.png"
}
Icon "water_slide" "classic" (Col 250 196 20) (Col 220 160 0)
Icon "water_slide_drop" "drop" (Col 47 127 224) (Col 60 200 160)
Icon "water_slide_spiral" "spiral" (Col 216 48 46) (Col 240 100 90)
Icon "water_slide_serpentine" "serpentine" (Col 240 144 30) (Col 255 196 90)

function W($path, $text) { [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding $false)) }
W "$a\models\block\invisible.json" '{ "textures": { "particle": "waterslide:block/particle" }, "elements": [] }'
W "$a\blockstates\part.json" '{ "variants": { "": { "model": "waterslide:block/invisible" } } }'
W "$a\blockstates\anchor.json" '{ "variants": { "facing=north": { "model": "waterslide:block/invisible" }, "facing=south": { "model": "waterslide:block/invisible" }, "facing=east": { "model": "waterslide:block/invisible" }, "facing=west": { "model": "waterslide:block/invisible" } } }'
foreach ($n in "water_slide","water_slide_drop","water_slide_spiral","water_slide_serpentine") {
  W "$a\models\item\$n.json" ('{ "parent": "minecraft:item/generated", "textures": { "layer0": "waterslide:item/' + $n + '" } }') }
"assets ok"
