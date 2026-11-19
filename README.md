# Fāito — mesurer des distances

App Android (et page web) pour mesurer des distances approximatives :

- **Photo / vidéo** : on trace un repère jaune sur un objet de taille connue (porte, A4, carte bancaire…), puis des mesures en bleu.
- **Caméra en direct** : on vise, on fige l'image, on mesure.
- **AR live** : mesure 3D sans repère (Chrome Android + ARCore). Dans l'APK, ce bouton ouvre la version web dans Chrome, car la vue intégrée d'Android ne gère pas l'AR.
- **Bouton MAJ** : vérifie la dernière Release GitHub, télécharge l'APK avec une barre de progression, puis lance l'installation.

## Mise en place (une seule fois)

1. Crée le dépôt **public** `faito` sur GitHub (compte Brian-1844).
2. Envoie **tout le contenu** de ce dossier, y compris le dossier caché `.github` et le dossier `keystore`.
3. **Settings → Pages → Source : GitHub Actions** (pour la version web, la caméra live et l'AR).
4. **Settings → Actions → General → Workflow permissions : Read and write**.

## Construire

Chaque envoi sur `main` lance **Actions → Construire l'APK**. Tu peux aussi le lancer à la main avec « Run workflow ».
À la fin :
- l'APK est dans **Releases** (`faito-vN.apk`) ;
- la version web est sur `https://brian-1844.github.io/faito/`.

Le numéro de version augmente automatiquement à chaque construction. Le bouton MAJ de l'app compare ce numéro à celui de la dernière Release.

## Première installation

Télécharge l'APK depuis Releases sur le téléphone et installe-le. Lors de la première mise à jour depuis l'app, Android demandera d'autoriser « Installer des applis inconnues » pour Fāito : autorise, reviens dans l'app, puis appuie sur **Installer**.

## Clé de signature

`keystore/faito.jks` signe tous les APK avec la même clé (sans ça, Android refuse la mise à jour).
Mot de passe par défaut : `faito2026`. Pour plus de sécurité, ajoute un secret `KEYSTORE_PASSWORD` et change le mot de passe de la clé. **Ne perds jamais ce fichier** : sans lui, il faudra désinstaller puis réinstaller l'app.

## Fichiers

- `www/` : l'app (HTML/JS)
- `native/` : code Android de la mise à jour intégrée (téléchargement + installation)
- `scripts/patch-android.sh` : ajoute ce code au projet Android
- `.github/workflows/build.yml` : construction, signature, Release et GitHub Pages
