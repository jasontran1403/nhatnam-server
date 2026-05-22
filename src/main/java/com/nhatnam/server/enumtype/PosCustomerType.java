// src/main/java/com/nhatnam/server/enumtype/PosCustomerType.java

package com.nhatnam.server.enumtype;

public enum PosCustomerType {
    KLE,   // Khách lẻ
    CTV,   // Cộng tác viên
    CTVV;  // Cộng tác viên vàng

    public String getLabel() {
        return switch (this) {
            case KLE  -> "Khách lẻ";
            case CTV  -> "Cộng tác viên";
            case CTVV -> "CTV Vàng";
        };
    }
}