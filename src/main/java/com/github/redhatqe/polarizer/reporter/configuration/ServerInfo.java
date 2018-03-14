package com.github.redhatqe.polarizer.reporter.configuration;


import com.fasterxml.jackson.annotation.JsonProperty;

public class ServerInfo {
    @JsonProperty(required = true)
    private String url;
    @JsonProperty
    private String domain;
    @JsonProperty(required = true)
    private String user;
    @JsonProperty(required = true)
    private String password;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public ServerInfo() {

    }

    public ServerInfo(String url, String user, String pw) {
        this.url = url;
        this.user = user;
        this.password = pw;
    }

    public ServerInfo(ServerInfo si) {
        this.url = si.getUrl();
        this.user = si.getUser();
        this.password = si.getPassword();
    }
}
