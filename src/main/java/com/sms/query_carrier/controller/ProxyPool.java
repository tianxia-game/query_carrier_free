package com.sms.query_carrier.controller;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyPool {

    public static final List<ProxyNode> proxies;
    private static final AtomicInteger index = new AtomicInteger(0);

    static {
        proxies = Arrays.asList(
                new ProxyNode("brd.superproxy.io", 33335, "brd-customer-hl_62c067d2-zone-isp_proxy1", "dxqw68y776zq")
              /*  new ProxyNode("216.231.56.49", 2000, "kaer1024", "kaer1024"),
                new ProxyNode("216.231.56.214", 2000, "kaer1024", "kaer1024"),
                new ProxyNode("70.39.239.165", 2000, "kaer1024", "kaer1024"),
                new ProxyNode("216.132.148.11", 2000, "kaer1024", "kaer1024")*/
        );
    }

    public static ProxyNode next() {
        if (index.get() > 100_0000) {
            index.set(0);
        }
        int i = Math.abs(index.getAndIncrement() % ProxyPool.proxies.size());
        return ProxyPool.proxies.get(i);
    }
}
