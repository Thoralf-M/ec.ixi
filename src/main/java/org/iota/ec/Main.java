package org.iota.ec;

import org.iota.ict.Ict;
import org.iota.ict.utils.properties.Properties;

public class Main {

    public static void main(String[] args) {
        Ict ict = new Ict(new Properties().toFinal());
        ECModule module = new ECModule(ict);
    }
}