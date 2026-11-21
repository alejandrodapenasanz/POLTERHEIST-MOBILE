package org.polterheist.mobile;

import java.net.URL;
import java.util.Set;

public final class DlnaDevice {
    public final String id, name, model, host, serviceType;
    public final URL control, schema;
    public final Set<String> actions;

    public DlnaDevice(String id, String name, String model, String host, String serviceType,
                      URL control, URL schema, Set<String> actions) {
        this.id = id; this.name = name; this.model = model; this.host = host;
        this.serviceType = serviceType; this.control = control; this.schema = schema; this.actions = actions;
    }
    public DlnaDevice withActions(Set<String> actions) {
        return new DlnaDevice(id, name, model, host, serviceType, control, schema, actions);
    }
    public boolean playable() { return actions.containsAll(Set.of("SetAVTransportURI", "Play", "Stop")); }
}
