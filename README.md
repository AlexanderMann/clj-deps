# clj-deps

[![CircleCI](https://circleci.com/gh/AlexanderMann/clj-deps.svg?style=svg)](https://circleci.com/gh/AlexanderMann/clj-deps)

CLJ-Deps is a brute force Clojure dependency inspector. Intended use is to give it a Github Organization (or User) name and then let it build a `clj-deps.edn` and `clj-deps.json` file.

It is not efficient, it is not pretty, but it is useful.

## Usage

The clj-deps format has two key fields:
- `nodes`: Individual dependencies in your org/repo/project.clj
- `edges`: `[dependent-node source-node]` : Dependencies from parent to Dependent to Source.

### Running it yourself!

The aim is for this project to eventually be built as a docker container so that you can simply pass in a few env vars and bam, be done. But, we're not there yet.

If you want to run this on your Organization do the following to get the credentials/token you need:

- https://help.github.com/articles/creating-a-personal-access-token-for-the-command-line/
- Be sure to select _all_ of the repos permissions to be able to clone your private repos (or don't, this will still work fine on your public)
- Place this either in a repl as the `token`, or in your env vars as `CLJ_DEPS__GH__TOKEN`

## Notes

This project works because of:
- [HTTPS Git Cloning](https://github.com/blog/1270-easier-builds-and-deployments-using-git-over-https-and-oauth)
- [Raynes/Tentacles](https://github.com/Raynes/tentacles#code)

## License

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
