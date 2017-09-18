#!/usr/bin/env bash

if [[ -z "$1" || "$1" = 'run' ]] ; then

  exec lein trampoline run

else

  exec lein trampoline repl :headless :port $NREPL_PORT :host 0.0.0.0

fi
