package com.jornco.controller.ble;

/**
 * Created by kkopite on 2017/10/25.
 */

public interface IronbotRule {
    boolean isRead(String uuid);
    boolean isWrite(String uuid);
}
