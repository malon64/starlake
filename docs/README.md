# Install Node
See https://itsromiljain.medium.com/the-best-way-to-install-node-js-npm-and-yarn-on-mac-osx-4d8a8544987a


# Website

This website is built using [Docusaurus 2](https://docusaurus.io/), a modern static website generator.

## Installation

```console
yarn install
```

## Local Development

```console
BASE_URL=/starlake/ yarn start 
```

This command starts a local development server and opens up a browser window. Most changes are reflected live without having to restart the server.

## Build

```console
BASE_URL=/starlake/ yarn build
```


    @@@@@@@@@@@@@@@@@@@@@
This command generates static content into the `build` directory and can be served using any static contents hosting service.

## Deployment

```console
GIT_USER=<Your GitHub username> USE_SSH=true BASE_URL=/starlake/ yarn deploy
```

If you are using GitHub pages for hosting, this command is a convenient way to build the website and push to the `gh-pages` branch.


Upgrade node: nvm install node --reinstall-packages-from=node
