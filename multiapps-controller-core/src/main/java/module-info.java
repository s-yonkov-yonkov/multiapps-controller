open module org.cloudfoundry.multiapps.controller.core {

    exports org.cloudfoundry.multiapps.controller.core;
    exports org.cloudfoundry.multiapps.controller.core.auditlogging;
    exports org.cloudfoundry.multiapps.controller.core.auditlogging.model;
    exports org.cloudfoundry.multiapps.controller.core.auditlogging.impl;
    exports org.cloudfoundry.multiapps.controller.core.cf;
    exports org.cloudfoundry.multiapps.controller.core.cf.apps;
    exports org.cloudfoundry.multiapps.controller.core.cf.clients;
    exports org.cloudfoundry.multiapps.controller.core.cf.detect;
    exports org.cloudfoundry.multiapps.controller.core.cf.metadata;
    exports org.cloudfoundry.multiapps.controller.core.cf.metadata.criteria;
    exports org.cloudfoundry.multiapps.controller.core.cf.metadata.entity.processor;
    exports org.cloudfoundry.multiapps.controller.core.cf.metadata.processor;
    exports org.cloudfoundry.multiapps.controller.core.cf.metadata.util;
    exports org.cloudfoundry.multiapps.controller.core.cf.util;
    exports org.cloudfoundry.multiapps.controller.core.cf.v2;
    exports org.cloudfoundry.multiapps.controller.core.cf.v3;
    exports org.cloudfoundry.multiapps.controller.core.configuration;
    exports org.cloudfoundry.multiapps.controller.core.health;
    exports org.cloudfoundry.multiapps.controller.core.health.model;
    exports org.cloudfoundry.multiapps.controller.core.helpers;
    exports org.cloudfoundry.multiapps.controller.core.helpers.escaping;
    exports org.cloudfoundry.multiapps.controller.core.helpers.expander;
    exports org.cloudfoundry.multiapps.controller.core.helpers.v2;
    exports org.cloudfoundry.multiapps.controller.core.helpers.v3;
    exports org.cloudfoundry.multiapps.controller.core.http;
    exports org.cloudfoundry.multiapps.controller.core.liquibase;
    exports org.cloudfoundry.multiapps.controller.core.model;
    exports org.cloudfoundry.multiapps.controller.core.parser;
    exports org.cloudfoundry.multiapps.controller.core.resolvers.v2;
    exports org.cloudfoundry.multiapps.controller.core.resolvers.v3;
    exports org.cloudfoundry.multiapps.controller.core.security.data.termination;
    exports org.cloudfoundry.multiapps.controller.core.security.serialization;
    exports org.cloudfoundry.multiapps.controller.core.security.token;
    exports org.cloudfoundry.multiapps.controller.core.security.token.parsers;
    exports org.cloudfoundry.multiapps.controller.core.util;
    exports org.cloudfoundry.multiapps.controller.core.validators.parameters;
    exports org.cloudfoundry.multiapps.controller.core.validators.parameters.v2;
    exports org.cloudfoundry.multiapps.controller.core.validators.parameters.v3;

    requires transitive com.sap.cloudfoundry.client.facade;
    requires transitive jakarta.persistence;
    requires transitive org.cloudfoundry.multiapps.controller.client;
    requires transitive org.cloudfoundry.multiapps.controller.persistence;
    requires transitive org.cloudfoundry.multiapps.mta;

    requires org.cloudfoundry.client;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.google.common;
    requires java.sql;
    requires jakarta.xml.bind;
    requires jakarta.inject;
    requires liquibase.core;
    requires org.apache.logging.log4j;
    requires org.apache.logging.log4j.core;
    requires org.apache.commons.collections4;
    requires org.apache.commons.io;
    requires org.apache.commons.lang3;
    requires org.apache.httpcomponents.httpclient;
    requires org.apache.httpcomponents.httpcore;
    requires org.cloudfoundry.multiapps.common;
    requires org.cloudfoundry.multiapps.controller.api;
    requires org.slf4j;
    requires org.yaml.snakeyaml;
    requires reactor.core;
    requires spring.beans;
    requires spring.context;
    requires spring.core;
    requires spring.security.core;
    requires com.nimbusds.jose.jwt;
    requires spring.web;
    requires spring.webflux;
    requires spring.security.oauth2.core;
    requires reactor.netty;
    requires io.netty.handler;

    requires static java.compiler;
    requires static org.immutables.value;
    requires spring.security.oauth2.client;

}