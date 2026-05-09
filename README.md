
# CobbleDollars Command Shops

Addon NeoForge 1.21.1 pour CobbleDollars.

Il ajoute uniquement des shops CobbleDollars personnalises appeles par commande.
Il ne remplace pas CobbleDollars, n'ajoute pas de PNJ, et n'utilise plus de merchant temporaire.
Le flux d'achat repasse par le serveur avec un stock persistant par joueur.

## Commandes

- `/cdshops open <shop>`
- `/cdshops open <shop> <joueur>`
- `/cdshops restock <shop> all <joueurs>`
- `/cdshops restock <shop> offer <offer> <joueurs>`
- `/cdshops stock <shop> <joueur>`
- `/cdshops list`
- `/cdshops where`

Les joueurs normaux ne peuvent pas lancer `/cdshops`.
Un moderateur peut l'utiliser, et un bouton CustomNPCs peut l'executer pour ouvrir le shop chez le joueur cible.

## Utilisation avec CustomNPCs

Depuis un PNJ ou la console, il faut fournir la cible explicitement.

Exemple script :

`npc.executeCommand("cdshops open armurier " + player.getName());`

## Dossier des boutiques

Les boutiques sont lues depuis :

`config/cobbledollarscommandshops/shops`

La bank personnalisee est lue depuis :

`config/cobbledollarscommandshops/bank.json`

## Format JSON

Exemple :

```json
{
  "id": "armurier",
  "categories": [
    {
      "name": "Armes",
      "offers": [
        {
          "id": "epee_fer",
          "item": "minecraft:iron_sword",
          "count": 1,
          "price": 90
        },
        {
          "id": "epee_diamant",
          "item": "minecraft:diamond_sword",
          "count": 1,
          "price": 450,
          "stock": 2,
          "restock": {
            "type": "interval",
            "amount": 1,
            "every_seconds": 600
          }
        }
      ]
    }
  ]
}
```

Champs supportes :

- `id`: identifiant du shop appele par la commande
- `categories[].name`: nom de categorie affiche dans le shop
- `offers[].id`: identifiant stable de l'offre, utilise pour le stock persistant
- `offers[].item`: identifiant exact de l'item a vendre
- `offers[].count`: quantite donnee a l'achat, `1` par defaut
- `offers[].price`: prix exact en CobbleDollars
- `offers[].stock`: stock maximum et stock initial du joueur, `-1` ou absent pour illimite

Restocks supportes :

- `interval`: ajoute `amount` toutes les `every_seconds`, jusqu'au stock max
- `daily_reset`: remet le stock au maximum chaque jour a `hour`:`minute`

Exemple de reset journalier :

```json
{
  "id": "daily_apple",
  "item": "minecraft:golden_apple",
  "price": 125,
  "stock": 3,
  "restock": {
    "type": "daily_reset",
    "hour": 4,
    "minute": 0,
    "time_zone": "Europe/Paris"
  }
}
```

Notes :

- le stock est persistant par joueur
- le restock est calcule cote serveur
- l'ancien format JSON par tableau reste accepte pour compatibilite, mais il reutilise alors le nom de fichier comme id du shop

## Format bank.json

Exemple :

```json
{
  "offers": [
    {
      "item": "minecraft:iron_ingot",
      "price": 8
    },
    {
      "item": "minecraft:diamond",
      "price": 75
    }
  ]
}
```

Champs supportes :

- `offers[].item`: item autorise a la vente dans la bank
- `offers[].price`: prix unitaire rendu au joueur
- `offers[].count`: optionnel, `1` par defaut

## Build

`gradlew build`
