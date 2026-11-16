# LS4TC421 : politique de confidentialité

*[English version](PRIVACY.md)*

Dernière mise à jour : 16/11/2026

LS4TC421 (« Light Scheduler for TC421 ») est une application Android qui programme un contrôleur
d'éclairage TC421 par Wi-Fi. Elle est publiée par RIC Labs (Sébastien Raison). Cette politique
s'applique à toutes les versions de l'application, qu'elle ait été installée depuis Google Play,
F-Droid ou GitHub.

## En bref

**LS4TC421 ne collecte aucune donnée personnelle.** Il n'y a ni compte, ni serveur géré par le
développeur, ni publicité, ni statistiques d'usage, ni rapport de plantage. L'application ne parle
qu'à votre contrôleur, sur votre réseau local. Tout ce que vous lui confiez reste sur votre
appareil.

LS4TC421 est un logiciel libre (GPL-3.0). Son code source est public, chaque affirmation de cette
page est donc vérifiable : <https://github.com/sebastienraison/LS4TC421>

## Données stockées sur votre appareil

LS4TC421 enregistre dans son espace privé, que les autres applications ne peuvent pas lire :

- vos contrôleurs : le nom que vous leur donnez, leur adresse sur votre réseau local, leur
  identifiant matériel (adresse MAC), et ce que l'application a écrit dans chacun de leurs
  emplacements ;
- vos programmes d'éclairage ;
- vos réglages (couleur de chaque canal, nom donné aux emplacements vidés).

Rien de tout cela n'est envoyé au développeur, ni à qui que ce soit.

**La clé de votre réseau Wi-Fi**, si vous la saisissez pour configurer un contrôleur, n'est jamais
enregistrée : elle est envoyée une fois au contrôleur, puis oubliée. Elle circule en clair sur
votre réseau local, car le protocole du contrôleur ne permet pas de la chiffrer.

Le **journal des échanges**, qui sert au diagnostic, n'existe qu'en mémoire : il disparaît à la
fermeture de l'application. Il ne quitte votre appareil que si vous le partagez vous-même. Le nom
et la clé de votre réseau Wi-Fi n'y figurent jamais ; l'identifiant matériel du contrôleur y est
en partie masqué ; les adresses de votre réseau local y restent visibles, car elles servent au
diagnostic.

## Votre position

LS4TC421 demande la permission de localisation **pour une seule raison** : Android ne révèle le nom
du réseau Wi-Fi auquel votre téléphone est connecté qu'aux applications qui ont cette permission.
L'application s'en sert pour vous proposer ce nom quand vous configurez le Wi-Fi d'un contrôleur.
**Elle ne lit jamais votre position.** Si vous refusez la permission, vous saisissez le nom du
réseau vous-même.

## Connexions réseau

LS4TC421 ne se connecte pas à Internet. Elle échange uniquement avec votre contrôleur, sur votre
réseau local (UDP, port 5000) : recherche des contrôleurs, envoi des programmes, réglage de l'heure
et du Wi-Fi.

Les liens des Réglages (code source, cette page) s'ouvrent dans votre navigateur ; c'est alors la
politique du site visité qui s'applique.

## Permissions

| Permission | Pourquoi |
|---|---|
| Internet, état du réseau, état du Wi-Fi | Échanger avec le contrôleur sur le réseau local, savoir si le téléphone est en Wi-Fi |
| Localisation (précise ou approximative) | Lire le nom du réseau Wi-Fi, comme expliqué plus haut ; jamais la position |
| Réseau local (Android 17 et suivants) | Joindre le contrôleur sur votre réseau |

## Sauvegardes

- **Vos fichiers de programmes.** Vous pouvez exporter un programme dans un fichier, et choisir
  où l'envoyer. Ce fichier vous appartient : LS4TC421 n'en garde aucune copie ailleurs.
- **La sauvegarde Android.** Si la sauvegarde est activée sur votre appareil, Android peut
  inclure les données de LS4TC421 (contrôleurs, programmes, réglages) dans la sauvegarde stockée
  sur votre compte Google. C'est une fonction d'Android, qui se règle dans les paramètres de votre
  appareil. Voir [la documentation de Google](https://support.google.com/android/answer/2819582).

## Sécurité

Vos données sont enregistrées dans l'espace privé de l'application, protégé par Android : les
autres applications n'y ont pas accès. Aucune donnée n'est transmise au développeur : il n'en
existe donc aucune copie ailleurs que sur votre appareil, dans vos propres sauvegardes et dans les
fichiers que vous exportez.

## Durée de conservation et suppression

Vos données restent sur votre appareil jusqu'à ce que vous les supprimiez. Vous pouvez :

- supprimer un contrôleur ou un programme depuis l'application ;
- effacer les données de l'application depuis les paramètres Android ;
- désinstaller l'application, ce qui supprime tout ce qu'elle a enregistré sur l'appareil.

Si la sauvegarde Android est activée, une copie peut subsister sur votre compte Google. Elle se
gère depuis les paramètres de sauvegarde de votre appareil.

Le développeur ne détient aucune de vos données : il n'a donc rien à supprimer de son côté.

## Cette page

Cette page est hébergée par GitHub. Comme tout hébergeur, GitHub enregistre l'adresse IP des
visiteurs, pour des raisons de sécurité. Ce traitement est régi par la
[déclaration de confidentialité de GitHub](https://docs.github.com/fr/site-policy/privacy-policies/github-general-privacy-statement),
et non par la présente politique, qui porte sur l'application.

## Contact

RIC Labs (Sébastien Raison)
<ls4tc421@rseb.net>

Vous pouvez aussi ouvrir un ticket : <https://github.com/sebastienraison/LS4TC421/issues>

## Révisions

| Date | Motif |
|---|---|
| 16/11/2026 | Première version |
