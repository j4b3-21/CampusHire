FROM quay.io/keycloak/keycloak:26.3

COPY realm/ /opt/keycloak/data/import/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start-dev", "--import-realm"]