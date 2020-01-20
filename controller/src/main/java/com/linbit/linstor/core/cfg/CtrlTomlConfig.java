package com.linbit.linstor.core.cfg;

import com.linbit.linstor.core.LinstorConfigTool;
import com.linbit.linstor.core.cfg.LinstorConfig.RestAccessLogMode;

public class CtrlTomlConfig
{
    static class HTTP
    {
        private Boolean enabled;
        private String listen_addr;
        private Integer port;

        public void applyTo(CtrlConfig cfg)
        {
            cfg.setRestEnabled(enabled);

            if (listen_addr != null || port != null)
            {
                String bindAddr = CtrlConfig.DEFAULT_HTTP_LISTEN_ADDRESS;
                int bindPort = CtrlConfig.DEFAULT_HTTP_REST_PORT;
                if (listen_addr != null)
                {
                    if (listen_addr.contains(":"))
                    {
                        bindAddr = "[" + listen_addr + "]";
                    }
                    else
                    {
                        bindAddr = listen_addr;
                    }
                }

                if (port != null)
                {
                    bindPort = port;
                }

                cfg.setRestBindAddressWithPort(bindAddr + ":" + bindPort);
            }
        }
    }

    static class HTTPS
    {
        private Boolean enabled;
        private String listen_addr;
        private Integer port;
        private String keystore;
        private String keystore_password;
        private String truststore;
        private String truststore_password;

        public void applyTo(CtrlConfig cfg)
        {
            cfg.setRestSecureEnabled(enabled);

            if (listen_addr != null || port != null)
            {
                String bindAddr = CtrlConfig.DEFAULT_HTTP_LISTEN_ADDRESS;
                int bindPort = CtrlConfig.DEFAULT_HTTPS_REST_PORT;
                if (listen_addr != null)
                {
                    if (listen_addr.contains(":"))
                    {
                        bindAddr = "[" + listen_addr + "]";
                    }
                    else
                    {
                        bindAddr = listen_addr;
                    }
                }

                if (port != null)
                {
                    bindPort = port;
                }

                cfg.setRestSecureBindAddressWithPort(bindAddr + ":" + bindPort);
            }

            cfg.setRestSecureKeystore(keystore);
            cfg.setRestSecureKeystorePassword(keystore_password);
            cfg.setRestSecureTruststore(truststore);
            cfg.setRestSecureTruststorePassword(truststore_password);
        }
    }

    static class LDAP
    {
        private Boolean enabled;
        private Boolean allow_public_access;
        private String uri;
        private String dn;
        private String search_base;
        private String search_filter;

        public void applyTo(CtrlConfig cfg)
        {
            cfg.setLdapEnabled(enabled);
            cfg.setLdapAllowPublicAccess(allow_public_access);
            cfg.setLdapUri(uri);
            cfg.setLdapDn(dn);
            cfg.setLdapSearchBase(search_base);
        }
    }

    public static class DB
    {
        private String user;
        private String password;
        private String connection_url;
        private String ca_certificate;
        private String client_certificate;
        /**
         * Typo in linstor version 1.2.1
         */
        @Deprecated
        private String client_key_pcks8_pem;
        private String client_key_pkcs8_pem;
        private String client_key_password;

        public void applyTo(CtrlConfig cfg)
        {
            cfg.setDbUser(user);
            cfg.setDbPassword(password);
            cfg.setDbConnectionUrl(connection_url);
            cfg.setDbCaCertificate(ca_certificate);
            cfg.setDbClientCertificate(client_certificate);
            cfg.setDbClientKeyPkcs8Pem(client_key_pkcs8_pem != null ? client_key_pkcs8_pem : client_key_pcks8_pem);
            cfg.setDbClientKeyPassword(client_key_password);
        }

        /**
         * Getter needed by {@link LinstorConfigTool}
         */
        public String getConnectionUrl()
        {
            return connection_url;
        }

        /**
         * Getter needed by {@link LinstorConfigTool}
         */
        public String getUser()
        {
            return user;
        }

        /**
         * Getter needed by {@link LinstorConfigTool}
         */
        public String getPassword()
        {
            return password;
        }
    }

    static class Logging
    {
        private String level;
        private String rest_access_log_path;
        private RestAccessLogMode rest_access_log_mode;

        public void applyTo(CtrlConfig cfg)
        {
            cfg.setLogLevel(level);
            cfg.setLogRestAccessLogPath(rest_access_log_path);
            cfg.setLogRestAccessMode(rest_access_log_mode);
        }
    }

    private HTTP http = new HTTP();
    private HTTPS https = new HTTPS();
    private LDAP ldap = new LDAP();
    private DB db = new DB();
    private Logging logging = new Logging();

    /**
     * Getter needed by {@link LinstorConfigTool}
     */
    public DB getDB()
    {
        return db;
    }

    public void applyTo(CtrlConfig cfg)
    {
        http.applyTo(cfg);
        https.applyTo(cfg);
        ldap.applyTo(cfg);
        db.applyTo(cfg);
        logging.applyTo(cfg);
    }
}