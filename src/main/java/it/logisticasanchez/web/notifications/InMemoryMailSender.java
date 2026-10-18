package it.logisticasanchez.web.notifications;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** The test sink: keeps what was sent for assertions and can be told to fail. */
public class InMemoryMailSender implements MailSender {

    private final List<OutboundEmail> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final AtomicInteger counter = new AtomicInteger();

    @Override
    public String send(OutboundEmail email) throws MailSendException {
        if (failuresLeft.get() > 0) {
            failuresLeft.decrementAndGet();
            throw new MailSendException("simulated delivery failure");
        }
        sent.add(email);
        return "memory-" + counter.incrementAndGet();
    }

    public List<OutboundEmail> sent() {
        return List.copyOf(sent);
    }

    /** The next {@code count} sends fail. */
    public void failNext(int count) {
        failuresLeft.set(count);
    }

    public void clear() {
        sent.clear();
        failuresLeft.set(0);
    }
}
