#!/usr/bin/env bash

exec lein trampoline repl :headless :port $NREPL_PORT :host 0.0.0.0
