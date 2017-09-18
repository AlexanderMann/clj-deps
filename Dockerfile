FROM clojure:lein-2.7.1-alpine

RUN apk update && apk upgrade && \
    apk add --no-cache bash git openssh

ADD . /code
WORKDIR /code

ENTRYPOINT ["./run"]
