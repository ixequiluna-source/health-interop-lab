package ai.firmus.interop.hl7;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records published events in order; used by tests and by the {@code --dry-run} mode. */
public final class InMemoryEventSink implements EventSink {

    private final List<AdmissionEvent> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(AdmissionEvent event) {
        published.add(event);
    }

    public List<AdmissionEvent> published() {
        return List.copyOf(published);
    }

    public int count() {
        return published.size();
    }
}
