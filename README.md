
# CobbleDollars Command Shops

Addon NeoForge 1.21.1 pour CobbleDollars.

Il ajoute uniquement des boutiques CobbleDollars personnalisees ouvertes par commande.
Il ne remplace pas CobbleDollars et ne gere pas les PNJ par lui-meme.
Les achats passent par un vrai marchand CobbleDollars cache, donc l'item est bien donne et la monnaie bien retiree.

## Commandes

- `/npcshop open <shop>`
- `/npcshop open <shop> <joueur>`
- `/npcshop list`
- `/npcshop where`

## Dossier des boutiques

Les boutiques sont lues depuis :

`run/config/cobbledollarscommandshops/shops`

Exemples fournis :

- `armurier.json`
- `example.json`

## Utilisation avec CustomNPCs

Depuis un PNJ ou la console, il faut maintenant fournir la cible explicitement.

Exemple script :

`npc.executeCommand("npcshop open armurier " + player.getName());`

Les joueurs normaux ne peuvent pas lancer `/npcshop` eux-memes.
Un moderateur peut l'utiliser, et un bouton CustomNPCs peut l'executer pour ouvrir le shop chez le joueur cible.

## Format JSON

Exemple :

```json
[
  {
    "Armes": [
      {
        "item": "minecraft:iron_sword",
        "count": 1,
        "price": 90
      },
      {
        "item": "minecraft:golden_apple",
        "count": 4,
        "price": 125,
        "stock": 12
      }
    ]
  }
]
```

Champs supportes :

- `item`: identifiant exact de l'item a vendre
- `count`: quantite donnee a l'achat, `1` par defaut
- `price`: prix exact en CobbleDollars
- `stock`: optionnel, `-1` par defaut pour un stock illimite

Notes de comportement :

- le stock eventuel est local a l'ouverture du shop et n'est pas persistant entre deux ouvertures
- un joueur normal ne peut pas lancer `/npcshop` lui-meme
- un moderateur peut l'utiliser, et un bouton CustomNPCs peut l'executer pour ouvrir le shop chez le joueur cible

## Build

`gradlew build`
