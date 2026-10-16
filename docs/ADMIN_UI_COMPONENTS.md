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

Les sélecteurs de format sonore et de réapprovisionnement conservent leurs
valeurs quand l'option courante est sélectionnée à nouveau. Les erreurs locales
sont affichées sous les champs, sans remplacer les composants partagés ; le
serveur effectue toujours la validation complète. Supprimer la dernière valeur
d'une règle ordonnée propose de supprimer cette règle entière.

Les messages et sons affichent les valeurs héritées en grisé. Personnaliser
copie les valeurs effectives de l'événement, tandis que rétablir les valeurs par
défaut retire la personnalisation. Écouter joue localement le son du brouillon,
avec son volume et sa hauteur, sans sauvegarde ni paquet envoyé au serveur.

Les sauvegardes et rechargements sont corrélés à leur demande et au brouillon
envoyé. Une réponse tardive est mise de côté si le brouillon a changé ou si la
demande a expiré ; le bouton Charger l'état serveur permet de la reprendre
explicitement. Fermer l'écran ou se déconnecter invalide ses demandes. Les
anciens serveurs restent consultables mais demandent une mise à jour avant
l'édition, car ils ne corrèlent pas les réponses de rechargement.

Le panneau des boutiques dispose d'un bouton d'activation/désactivation et d'un
message de refus optionnel. L'onglet Stocks, placé entre Banques et Paramètres,
permet de consulter, modifier et réapprovisionner le stock d'un joueur. Ces
actions sont appliquées immédiatement par le serveur, avec vérification des
permissions et actualisation des sessions concernées. Elles ne modifient pas
le brouillon de configuration et ne dépendent pas du bouton Enregistrer.
Les changements de visibilité et la gestion manuelle des stocks sont accessibles
uniquement depuis `/cdshops admin`, avec le niveau de permission 2 et le mod
installé sur le client de l'administrateur. Les identifiants des boutiques restent
consultables dans la liste du panneau.

Exécution ciblée :

```powershell
./gradlew.bat test --tests '*UiFormInteractionTest' --tests '*UiColorCodecTest'
```

Le scénario opt-in `ClientAdminUiSmokeTest` ouvre un client Minecraft réel avec
un brouillon détaché, vérifie les formulaires et capture dix vues : boutique,
liste des offres, offre, messages, banque, liste des conditions, condition individuelle,
valeurs héritées des messages, erreur de saisie et stocks du joueur.
Il ferme le client après les captures sans envoyer de
sauvegarde ni modifier les configurations du serveur.

```powershell
./gradlew.bat runClient '-Pcommandshops.adminUiSmokeTest=true'
```

Les captures sont écrites dans `run/screenshots/admin-root.png`,
`admin-offers.png`, `admin-offer.png`, `admin-feedback.png`, `admin-bank.png`,
`admin-conditions.png`, `admin-condition.png`, `admin-feedback-defaults.png` et
`admin-inline-error.png` et `admin-stocks.png`.
Le journal indique `CLIENT_ADMIN_UI_SMOKE_RESULT: PASS` après les dix rendus.
Le scénario vérifie aussi les aides et les infobulles des boutons de liste,
ainsi que le placement du nom et des actions sur une même ligne compacte, sans
chevauchement entre l'aide et les boutons.
`ClientAdminLiveSmokeTest` vérifie les réponses corrélées, la conservation du
brouillon, les stocks distincts des variantes, les limites de saisie et la
confirmation des modifications visant tous les joueurs connectés.
`ClientShopOfferOrderSmokeTest` vérifie le widget natif réel : ordre configuré,
arrivée tardive des données, sélection, recherche, changement de catégorie et
isolation des autres marchands. Ce scénario ne simule pas de session multijoueur.
`ClientAdminLocalizationSmokeTest` vérifie les ressources françaises, anglaises
et le repli vers l'anglais, puis le passage d'un message localisé jusqu'à l'écran.
Il couvre aussi les huit motifs de refus par défaut et la priorité des messages
personnalisés et historiques. Le formulaire des règles vérifie que sélectionner
le type déjà actif conserve ses valeurs et son message.
`ClientAdminUsabilitySmokeTest` vérifie les sélections de son/réapprovisionnement,
la suppression d'une condition entière, les valeurs héritées et la préécoute
locale avec le véritable gestionnaire de sons.
`ClientAdminOperationSmokeTest` passe par le récepteur de paquets, le bridge et
le véritable écran pour vérifier les retards, nouvelles modifications,
opérations remplacées, renouvellements de session et fermetures/déconnexions.
Les tests du document, de la persistance et du transport couvrent séparément
les brouillons, les grandes valeurs, les conflits, la validation, le rollback
et les transferts fragmentés.
