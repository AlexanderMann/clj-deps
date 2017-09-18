# clj-deps

[![CircleCI](https://circleci.com/gh/AlexanderMann/clj-deps.svg?style=svg)](https://circleci.com/gh/AlexanderMann/clj-deps)

[DockerHub](https://hub.docker.com/r/mannimal/clj-deps/)

---

CLJ-Deps is a brute force Clojure dependency inspector. Intended use is to give it a Github Organization (or User) name and then let it build a `clj-deps.edn` and `clj-deps.json` file.

It is not efficient, it is not pretty, but it is useful.

## Usage

_NOTE: The following description is NOT as good as the specs actually used in testing everything!!! See here: https://github.com/AlexanderMann/clj-deps/blob/master/src/clj_deps/graph.clj for a full description of the supported format_

The clj-deps format has a few key fields:
- `desc` : `string`
  - A description of what you're looking at. No format or recommendation here.
- `at` : `Date`
  - The date at which this `graph` was built.
- `root` : `UID`
  - The root `Node` in this `graph`
  - The presence of this makes chaining graphs _very_ simple. (linked lists anyone?)
- `nodes`: Individual dependencies in your org/repo/project.clj

A `UID` (Unique ID) has the following format:
- `id` : `[string ...]`
  - A vector of string identifiers for a node.
- `type` : `org|repo|project|version`
  - This identifies the _type_ of node you're looking at. ie, is this representing a Github `org`anization, or a Project `version`, etc.

`Node`s have the following format:
- `uid` : `UID`
- `children` : `[UID ...]`
  - A vector of `UID`s describing children of this node. ie, things this node depends on
  - The nomenclature here is a little on its head since your children shouldn't be your dependencies...
  - The relationship between a `Node` and its `children` is as follows:
    - `org` `Nodes` only have `repo` `children`
    - `repo` `Nodes` only have `project` `children`
    - `project` `Nodes` only have `version` `children`
    - `version` `Nodes` only have `version` `children`
  - That is, you can purely filter out all `Node`s except `version` `Node`s and still get all of the benefit of a `clj-deps` `graph`. `org`, `repo`, and `project` nodes provide depth/detail to your graph, and increase the reporting possibilities.

### Running it yourself!

The way we use this project is as a docker container in a CircleCI Workflow.

Something like the following in your `.circle/config.yml` will get you running (and if you don't use CCI, you should be able to decipher usage from the following):

```
version: 2.0

workflows:
  version: 2
  build-and-deploy:
    jobs:
      - clj-deps
        #  context: org-global # Might be necessary depending on where you dump your CLJ_DEPS__GH__TOKEN

jobs:
  clj-deps:
    docker:
      - image: mannimal/clj-deps:latest
        environment:
          CLJ_DEPS__GH__ORG: AlexanderMann
          # CLJ_DEPS__GH__TOKEN: <present in project env vars/org-global context>
    working_directory: /code/
    steps:
      - store_artifacts:
          path: storage
          destination: clj-deps-output
```

After you get the output in your build, artifacts you can take a `clj-deps.json` file link and paste it in [here](https://annguy3n.github.io/cinnamon/) to get an awesome graph such as the following:

<img width="1018" alt="screen shot 2017-09-18 at 09 59 32" src="https://user-images.githubusercontent.com/3885029/30550848-4c13f792-9c5e-11e7-94df-a3bb807e5ebe.png">

### How to get a proper GitHub Token

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
