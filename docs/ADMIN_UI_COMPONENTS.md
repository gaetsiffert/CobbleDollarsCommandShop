# Composants de l'interface administrateur

L'interface reprend le framework interne de l'interface administrateur de
`Cobblemon_Expeditions_reworked-1.21.1`. Ce framework ne repose sur aucune
bibliothèque de configuration externe : il dessine ses composants avec
`GuiGraphics` et utilise les API Minecraft, Kotlin et GLFW.

La source consultée est
`D:\Desktop\Intellij projects\Mod minecraft\neoforge\Cobblemon_Expeditions_reworked\Cobblemon_Expeditions_reworked-1.21.1\src\main\kotlin\com\cobblemonexpeditions\gui\framework`.
L'extension de bordure provient de `gui/GuiCompat.kt` dans le même projet.

## Provenance et attribution

Le projet d'origine attribue sa base à ZIPender, puis les modifications et les
outils d'administration à Gaetan. Les fichiers repris conservent ces noms dans
un commentaire de provenance. La reprise des composants demandée pour ce mod
ne change pas l'attribution ni les droits de leurs auteurs ; elle ne constitue
pas une nouvelle licence générale pour le projet de référence.

## Fichiers repris

Les fichiers se trouvent dans
`src/main/kotlin/fr/cobbledollars/commandshops/client/ui` :

| Groupe | Composants |
| --- | --- |
| Contrats et mise en page | `FieldBinding`, `LambdaFieldBinding`, `UiRect`, `UiComponent`, `BaseUiComponent`, `UiForm`, `UiVerticalList` |
| Rendu partagé | `UiChrome`, `UiPanelStyle`, `UiButtonStyle`, `AdminUiTheme`, `UiHelpInlineLabel`, `UiIcons`, `UiModalChrome`, `UiText`, `UiTextWrap` |
| Défilement | `UiScroll`, `UiScrollPanel` |
| Champs de formulaire | `UiLabeledRowComponent`, `UiSingleLineEditorState`, `UiTextFieldComponent`, `UiToggleComponent`, `UiSelectComponent`, `UiMultiSelectComponent`, `UiItemFieldComponent`, `UiColorFieldComponent` |
| Organisation | `UiSectionHeaderComponent`, `UiSpacerComponent` |
| Édition de commandes | `UiRewardCommandsComponent` |
| Fenêtres superposées | `UiDropdownOverlay`, `UiItemPickerOverlay`, `UiColorPickerOverlay` |
| Couleurs et compatibilité | `UiColorCodec`, extension `GuiGraphics.drawBorder` |

Les modèles métier, les documents de contenu, les écrans et la couche réseau
du mod Expeditions ne sont pas des dépendances de ce mod.

## Adaptations

- Le package `com.cobblemonexpeditions.gui.framework` devient
  `fr.cobbledollars.commandshops.client.ui`, en conservant les sous-packages.
- `ExpeditionUiTheme` devient `AdminUiTheme` ; les couleurs, bordures et tailles
  des composants sont conservées.
- Le préfixe de traduction des composants devient
  `gui.cobbledollarscommandshops.admin.ui.`.
- Les libellés d'issues de commandes passent par le traducteur générique
  `UiText.admin`, sans référence aux issues propres aux expéditions.
- Seule l'extension `drawBorder` de `GuiCompat.kt` est nécessaire et reprise.

Les paramètres du panneau de droite disposent d'un marqueur `?` avec une aide
en français et en anglais. Les boutons possèdent des infobulles décrivant leur
action ; les noms des entrées de liste sont affichés sur la même ligne que leurs boutons.
Un clic sur un contrôle actif joue le son discret `ui.button.click`. Les aides
ne déclenchent aucune action lorsqu'on clique sur leur marqueur.

Le client conserve l'ordre configuré des offres pour les sessions de boutiques
de ce mod, y compris après une recherche. Le tri natif de CobbleDollars reste
appliqué aux autres marchands.

Les messages courants d'administration, de transfert et de sauvegarde sont
résolus dans la langue du client, avec un texte anglais de repli. Le protocole
transmet leur clé et leurs arguments séparément ; les clients qui ne disposent
pas de cette extension reçoivent le message anglais par le canal historique.
Les diagnostics détaillés des parseurs et les messages système restent en anglais.

Les noms de nouvelles catégories et le message de refus proposé dans l'éditeur
utilisent la langue active lors de leur création. Une fois enregistrés, ces
textes restent du contenu personnalisable, conservé lors des copies et des
rechargements.

`UiForm` distribue le focus, la saisie et le défilement à ses composants. Un
champ texte valide sa saisie en quittant le focus ou avec Entrée ; Échap
restaure la valeur du binding. L'écran appelle donc `clearFocus()` avant
d'enregistrer. La validation métier des valeurs reste dans les bindings et
dans le traitement de l'enregistrement sur le serveur.

## Vérification

`UiFormInteractionTest` vérifie le transfert du focus et de la saisie, la
libération du champ avant une action extérieure, le ciblage après défilement
et le recalage après réduction d'un composant dynamique.
`UiColorCodecTest` vérifie les échanges hexadécimal/RGB, les saisies invalides
et les limites des canaux.

Exécution ciblée :

```powershell
./gradlew.bat test --tests '*UiFormInteractionTest' --tests '*UiColorCodecTest'
```

Le scénario opt-in `ClientAdminUiSmokeTest` ouvre un client Minecraft réel avec
un brouillon détaché, vérifie les formulaires et capture cinq vues : boutique,
liste des offres, offre, messages et banque. Il ferme le client après les captures sans envoyer de
sauvegarde ni modifier les configurations du serveur.

```powershell
./gradlew.bat runClient '-Pcommandshops.adminUiSmokeTest=true'
```

Les captures sont écrites dans `run/screenshots/admin-root.png`,
`admin-offers.png`, `admin-offer.png`, `admin-feedback.png` et `admin-bank.png`.
Le journal indique `CLIENT_ADMIN_UI_SMOKE_RESULT: PASS` après les cinq rendus.
Le scénario vérifie aussi les aides et les infobulles des boutons de liste,
ainsi que le placement du nom et des actions sur une même ligne compacte, sans
chevauchement entre l'aide et les boutons.
`ClientShopOfferOrderSmokeTest` vérifie le widget natif réel : ordre configuré,
arrivée tardive des données, sélection, recherche, changement de catégorie et
isolation des autres marchands. Ce scénario ne simule pas de session multijoueur.
`ClientAdminLocalizationSmokeTest` vérifie les ressources françaises, anglaises
et le repli vers l'anglais, puis le passage d'un message localisé jusqu'à l'écran.
Les tests du document, de la persistance et du transport couvrent séparément
les brouillons, les grandes valeurs, les conflits, la validation, le rollback
et les transferts fragmentés.
