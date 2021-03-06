#!/bin/bash

if [ "$SERVERDIRNAME" == "" ]; then
  SERVERDIRNAME=defaultServer
else
  # Share the configuration directory via symlink
  ln -s /opt/ibm/wlp/usr/servers/defaultServer /opt/ibm/wlp/usr/servers/$SERVERDIRNAME

  # move the convenience output dir link to the new output location
  rm /output
  ln -s $WLP_OUTPUT_DIR/$SERVERDIRNAME /output
fi

SERVER_PATH=/opt/ibm/wlp/usr/servers/$SERVERDIRNAME
mkdir -p ${SERVER_PATH}/configDropins/overrides

if [ "$ETCDCTL_ENDPOINT" != "" ]; then
  echo Setting up etcd...
  echo "** Testing etcd is accessible"
  etcdctl --debug ls
  RC=$?

  while [ $RC -ne 0 ]; do
      sleep 15

      # recheck condition
      echo "** Re-testing etcd connection"
      etcdctl --debug ls
      RC=$?
  done
  echo "etcdctl returned sucessfully, continuing"

  mkdir -p ${SERVER_PATH}/resources/security
  cd ${SERVER_PATH}/resources/
  etcdctl get /proxy/third-party-ssl-cert > cert.pem
  openssl pkcs12 -passin pass:keystore -passout pass:keystore -export -out cert.pkcs12 -in cert.pem
  keytool -import -v -trustcacerts -alias default -file cert.pem -storepass truststore -keypass keystore -noprompt -keystore security/truststore.jks
  keytool -genkey -storepass testOnlyKeystore -keypass wefwef -keyalg RSA -alias endeca -keystore security/key.jks -dname CN=rsssl,OU=unknown,O=unknown,L=unknown,ST=unknown,C=CA
  keytool -delete -storepass testOnlyKeystore -alias endeca -keystore security/key.jks
  keytool -v -importkeystore -srcalias 1 -alias 1 -destalias default -noprompt -srcstorepass keystore -deststorepass testOnlyKeystore -srckeypass keystore -destkeypass testOnlyKeystore -srckeystore cert.pkcs12 -srcstoretype PKCS12 -destkeystore security/key.jks -deststoretype JKS
  cd ${SERVER_PATH}

  export service_map=$(etcdctl get /room/mapurl)
  export service_room=$(etcdctl get /room/service)
  export MAP_KEY=$(etcdctl get /passwords/map-key)
  export LOGSTASH_ENDPOINT=$(etcdctl get /logstash/endpoint)
  export SYSTEM_ID=$(etcdctl get /global/system_id)
  export KAFKA_URL=$(etcdctl get /kafka/url)
  export KAFKA_USER=$(etcdctl get /kafka/user)
  export KAFKA_PASSWORD=$(etcdctl get /passwords/kafka)

  #to run with message hub, we need a jaas jar we can only obtain
  #from github, and have to use an extra config snippet to enable it.
  mv ${SERVER_PATH}/configDropins/messageHub.xml ${SERVER_PATH}/configDropins/overrides
  wget https://github.com/ibm-messaging/message-hub-samples/raw/master/java/message-hub-liberty-sample/lib-message-hub/messagehub.login-1.0.0.jar

  exec /opt/ibm/wlp/bin/server run defaultServer
else
  exec /opt/ibm/wlp/bin/server run defaultServer
fi
