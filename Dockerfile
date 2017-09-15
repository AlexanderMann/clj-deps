FROM clojure:alpine

RUN apk update && apk upgrade && \
    apk add --no-cache bash git openssh

ADD . /code
WORKDIR /code

ENTRYPOINT ["./docker-entrypoint.sh"]
