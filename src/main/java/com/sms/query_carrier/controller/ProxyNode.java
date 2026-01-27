package com.sms.query_carrier.controller;

import lombok.Getter;

@Getter
public class ProxyNode {

    private final String host;
    private final int port;
    private final String username;
    private final String password;


    public ProxyNode(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }
}
