# Flatout Car (NeoForge 1.21.1)

Машина, которую можно водить; по клавише **G** игрок вылетает вперёд (по ходу машины) и переходит
в рэгдолл мода **Sable: Ragdolls** (`RagdollAPI.launch`). Параметры рэгдолла (длительность, выход, урон)
берутся из конфига самого Sable: Ragdolls.

## Управление
- ПКМ по машине: сесть. Shift: выйти.
- W / S: газ / тормоз и задний ход. A / D: руль. **G**: вылет через лобовое стекло (меняется в настройках).
- Получить: креатив, вкладка «Инструменты», либо `/give @s flatoutcar:car_spawner`, либо `/summon flatoutcar:car`.
- Удар игрока по машине (не из неё): машина превращается в предмет.

## Сборка
Нужна Java 21.
1. Скачайте официальный MDK для NeoForge 1.21.1 (ModDevGradle) со страницы NeoForged.
2. В папку MDK скопируйте из этого архива: `build.gradle`, `settings.gradle`, `gradle.properties` и папку `src`
   (замените существующие; пример-класс из MDK удалите).
3. В папке запустите `gradlew build` (Windows: `gradlew.bat build`).
4. Готовый мод: `build/libs/flatoutcar-1.21.1-1.0.0.jar`. Положите в `mods/` рядом с остальными.

## Настройка
Скорость, разгон и руление: константы в начале `CarEntity.java`.
Высота посадки: `SEAT_ATTACH_Y` в `FlatoutCarMod.java`.
Модель: `client/CarRenderer.java` (кубы) + `textures/entity/car.png` (128x128).
