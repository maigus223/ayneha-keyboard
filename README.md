# AYNEHA Keyboard — projet Android (Kotlin)

Application clavier (IME) native Android construite à partir de :
- `AYNEHA_Keyboard_Config.json` (disposition 10-10-7, PUA, variantes appui long)
- `ayneha_pro.ttf` v3.0 (police AYNEHA de transition, SIL OFL 1.1 : ancienne plage U+E000-U+E061 + nouvelle plage U+10FF7C-U+10FFDD)
- Les captures d'écran de l'aperçu web (thème, comportement RTL, pavé, emoji, symboles)

## Ouvrir le projet

1. Décompresser le zip.
2. Ouvrir le dossier `AynehaKeyboard` dans **Android Studio** (Open an existing project).
3. Laisser Android Studio synchroniser Gradle (il régénère le wrapper si besoin).
4. Lancer sur un appareil/émulateur (`minSdk 24`).

## Ce que fait le projet

- **`MainActivity`** — écran d'accueil : activer le clavier dans les réglages système, le tester, ou ouvrir les réglages.
- **`AynehaInputMethodService`** — le véritable clavier (IME) système, utilisable dans n'importe quelle app.
- **`TestActivity`** — zone de test intégrée en plein écran (zone de composition RTL agrandie + clavier intégré directement, sans passer par l'IME système), fidèle à l'aperçu web fourni.
- **`SettingsActivity`** — activation/désactivation du retour haptique.
- **`KeyboardLayouts.kt`** — parseur du JSON (`assets/ayneha_keyboard_config.json`) en modèle de données (`alphabet`, `symbols_page1`, `numpad`).
- **`AynehaKeyboardView.kt`** — vue custom qui dessine et gère le clavier :
  - **Placement RTL strict** : `keys[0]` de chaque rangée = touche à l'extrême droite de l'écran, comme spécifié (`"Render items in array from RIGHT to LEFT"`).
  - **Bulle d'appui long** : n'affiche QUE les variantes (jamais la touche de base), disposée en RTL (`layoutDirection = RTL`) pour respecter l'ordre Double → Muet / Double → Nasalisé → Ouvert du JSON.
  - **Répétition sur appui maintenu** pour la touche Effacer (⌦).
  - **Icônes des touches Effacer/Entrer orientées vers la droite** (`⌦` erase-to-the-right, `↴` mirroir de la flèche entrée), cohérent avec le sens de lecture RTL du clavier.
  - **Icônes de touches (action/changement de vue) agrandies ×2** par rapport aux touches lettres, pour rester lisibles et faciles à toucher.
  - **Page emoji défilante** : `layoutCurrentRows` calcule désormais une hauteur de rangée de référence (celle d'un clavier standard à 4 rangées) ; une page avec plus de rangées (ex. `emoji`, ~19 rangées) déborde simplement et devient scrollable verticalement (glisser le doigt), sans écraser la taille des touches.
  - **Retour haptique** configurable.
  - Police AYNEHA chargée depuis `res/font/ayneha_pro.ttf`.

## Version 2.5.0 - migration vers le plan 16 (U+10FF7C-U+10FFDD)

Les 98 glyphes AYNEHA quittent la zone privée du plan de base (U+E000-U+E061) pour le bloc réservé U+10FF7C-U+10FFDD (plan 16, 130 points réservés dont 32 de marge libre).

1. **Police** : `res/font/ayneha_type.ttf` remplacée par `res/font/ayneha_pro.ttf` (v3.0, transition : contient l'ancienne ET la nouvelle plage). Les 4 références `R.font` pointent vers `ayneha_pro`.
2. **Disposition** : `assets/ayneha_keyboard_config.json` utilise les nouveaux codes (`"pua": "U+10FF9C"`, avec en plus `"pua_utf16"` : la paire de substitution correspondante, à titre de référence ; le code ne lit que `pua` et convertit avec `Character.toChars`, qui gère déjà les points de code hors plan de base).
3. **Paires de substitution (UTF-16)** : un glyphe du plan 16 occupe 2 unités. Chaque glyphe saisi est donc enveloppé en RLO + 2 unités + PDF = **4 unités** (au lieu de 3).
   - `AynehaKeyboardEditing.backspaceLength()` : renvoie 4 (plan 16), 3 (ancien format BMP, conservé), 2 (paire de substitution isolée, ex. emoji : on ne coupe jamais une paire en deux) ou 1.
   - `AynehaInputMethodService` et `TestActivity` demandent maintenant 4 unités avant le curseur (`MAX_WRAPPER_UNITS`).
   - `TestActivity` (placement du curseur au toucher) utilise `AynehaKeyboardEditing.unitLengthAt()` au lieu de la valeur fixe 3.
4. **Libellés des touches** (`AynehaGlyphs` : `؟321`, `IEA`, touche espace « AYNEHA ») : codes recalculés (ancien code + 0x101F7C), même ordre et mêmes inversions qu'avant.
5. Aucune autre correction précédente n'est touchée (relayout bidi complet, hauteur fixe, icônes vectorielles, image de partage, bannière de support de police, haptique, etc.).
6. Version : `versionName` 2.5.0, `versionCode` 4.

Règle permanente pour tout le code AYNEHA : ne jamais supposer qu'un glyphe = 1 `char`. Utiliser les fonctions « code point » (`Character.toChars`, `codePointAt`, `String.fromCodePoint`, `Array.from`).

## Modifications apportées suite à la dernière demande (cohérence inter-pages + charte graphique)

1. **Ligne du bas identique sur les 4 pages** : en comparant les JSON, le pavé numérique et la page emoji avaient des largeurs de touches différentes de celles utilisées par les pages Lettres/Symboles (par ex. le pavé numérique utilisait une barre d'espace à poids `3.0` au lieu de `4.5`, et la page emoji avait des touches en trop qui ne faisaient pas partie du motif d'origine). La ligne du bas des 4 pages utilise maintenant exactement le même jeu de touches et les mêmes largeurs relatives (`؟321`/`IEA` = 1.5, virgule = 1.0, 😊 = 1.0, espace = 4.5, point = 1.0, entrée = 1.5).
2. **Touches Majuscule/Effacer (page Lettres) et `=\<`/Effacer (page Symboles)** : elles utilisaient déjà la même valeur de largeur JSON (1.25) et les mêmes icônes vectorielles (voir la fix précédente) — aucun changement structurel nécessaire, elles sont déjà cohérentes entre les deux pages.
3. **Charte graphique appliquée à toute l'application** : `colors.xml` a été entièrement réécrit pour correspondre exactement à la palette CSS fournie (fond `#0D1117`, touches standards `#161B22`, touches spéciales (Majuscule/`?123`/Effacer) `#21262D`, accent orange `#FF9F1C` pour Entrée/liens/focus, texte `#E6EDF3`). Le rayon d'arrondi des touches passe de 6dp à 12dp pour correspondre au token `--radius: 0.75rem`. `styles.xml` a aussi été corrigé (la barre de statut utilisait par erreur la couleur de texte, désormais claire, comme couleur "dark" — elle référence maintenant le vrai fond sombre).

## Modifications apportées lors de la demande précédente (icônes fiables + placement verrouillé)

Tes captures montraient des icônes Majuscule/Effacer incohérentes (case vide "tofu", formes qui ne correspondent pas à ce qui était prévu). Cause trouvée : la police AYNEHA (police AYNEHA) ne contient que 100 glyphes (lettres/chiffres/ponctuation AYNEHA + ASCII de base) — elle ne couvre AUCUN des caractères flèche/effacement (`⇧`, `⌦`, `↴`…). Ces touches retombaient donc sur la police de secours du système, dont le rendu varie d'un appareil à l'autre (d'où les icônes cassées).

1. **Icônes Majuscule/Effacer/Entrer dessinées en vectoriel** : ces 3 touches ne dépendent plus d'aucune police — elles sont dessinées directement au `Canvas` (`drawShiftIcon`, `drawBackspaceIcon`, `drawEnterIcon` dans `AynehaKeyboardView.kt`). Rendu strictement identique sur tous les appareils, quelle que soit la police système de secours.
2. **Placement verrouillé en LTR** : le calcul RTL des touches (`layoutCurrentRows`) est fait à la main en coordonnées LTR ; si la langue système de l'appareil est elle-même RTL, Android peut essayer de mirer une seconde fois la vue au niveau du système, ce qui annule notre propre placement. La vue clavier (`AynehaKeyboardView`) et son conteneur dans l'IME (`AynehaInputMethodService`) sont maintenant explicitement figés en `LAYOUT_DIRECTION_LTR`, pour que notre calcul RTL reste la seule source de vérité.

## Modifications apportées suite à la demande précédente

1. **Icônes Effacer/Entrer vers la droite** : `⌫`→`⌦` et `↵`→`↴` dans `ayneha_keyboard_config.json` (toutes les pages), plus le changement de couleur/police déjà en place.
2. **Saisie ramenée de droite vers la gauche** : `activity_test.xml` fixe désormais explicitement `android:layoutDirection="rtl"` sur la zone de composition (au lieu de dépendre de la locale de l'appareil). Plus important : les glyphes AYNEHA sont des points de code PUA (Private Use Area) sans classe bidi Unicode définie, donc classés forts-LTR par défaut — ce qui faisait grandir le texte tapé vers la droite au lieu de la gauche. Chaque glyphe AYNEHA inséré est maintenant enveloppé dans un contrôle Unicode RLO…PDF (`wrapRtl()` dans `KeyboardLayouts.kt`), qui force son affichage en RTL : le texte grandit désormais de droite à gauche et Effacer retire le dernier caractère tapé (le plus à gauche visuellement) — exactement le même principe de saisie/effacement qu'en Arabe ou en N'Ko. La touche Effacer (dans `AynehaInputMethodService` et `TestActivity`) supprime maintenant les 3 unités du bloc RLO-glyphe-PDF en un seul appui, via `AynehaKeyboardEditing.backspaceLength()`.
3. **Page emoji** : disposition `emoji` ajoutée dans le JSON (≈190 emoji sur rangées scrollables), accessible via le bouton 😊 déjà câblé. La rangée de navigation (retour "IEA", effacer, entrer) est placée en **première rangée** de la page emoji (visible immédiatement, sans avoir à défiler) pour un retour facile vers le clavier de lettres. Le rendu gère le défilement vertical (glisser le doigt) puisque toutes les rangées ne tiennent pas à l'écran.
4. **Tailles des icônes** : les lettres, chiffres et caractères standards internationaux (touches `CHAR`) ainsi que la plupart des icônes d'action/changement de vue sont doublés. La touche `=\<` (bascule vers le pavé numérique), dont le libellé à 3 glyphes débordait une fois doublé, a été ramenée à sa taille de moitié.

## Points restants (à valider avec toi)

1. **Touche `⇧` (`shift_or_switch`)** : le JSON la décrit comme une action à part. Le projet étant désormais purement Songhay (monocamérale, pas de bascule de langue), cette touche est actuellement **inerte** (aucune action câblée). À me dire ce qu'elle doit faire — rien, ou une autre fonction/action — et je la cible.
2. **Libellés `؟321` et `IEA`** : affichés avec les glyphes AYNEHA réels (chiffres 3-2-1 et voyelles I-E-A) plutôt qu'en texte latin, avec inversion explicite de la chaîne pour compenser le fait que les caractères PUA sont par défaut classés LTR par l'algorithme bidi Unicode (`AynehaGlyphs` dans `KeyboardLayouts.kt`). À vérifier visuellement sur l'appareil — l'ordre exact peut nécessiter un ajustement fin une fois affiché avec la vraie police.
3. Le projet n'a pas été compilé dans cet environnement (pas d'accès réseau à `dl.google.com`/Gradle) — à valider via un premier build/sync dans Android Studio.

## Structure

```
AynehaKeyboard/
  app/src/main/
    java/com/ayneha/keyboard/
      KeyboardLayouts.kt          (modèle + parseur JSON)
      KeyboardActionListener.kt
      AynehaKeyboardView.kt       (rendu + tactile + bulle de variantes)
      AynehaInputMethodService.kt (IME système)
      MainActivity.kt
      TestActivity.kt             (zone de test plein écran)
      SettingsActivity.kt
    res/font/ayneha_pro.ttf
    res/xml/method.xml
    res/layout/*.xml
    res/values/{strings,colors,styles}.xml
    assets/ayneha_keyboard_config.json
    AndroidManifest.xml
```
