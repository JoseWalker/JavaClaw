package ai.javaclaw.chat.ui;

import com.vaadin.flow.shared.Registration;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Asks the model, once, what a conversation should be called.
 *
 * <h2>Why the opening words are not enough</h2>
 *
 * <p>{@link ConversationTitles} reduces the first message to its topic, which is the best a
 * deterministic rewrite can do and is not good enough on the traffic this application actually sees.
 * Six threads opened with the same question reduce to the same topic, and the column then separates
 * them with an occurrence counter - six rows whose only distinguishing character is a bare ordinal.
 * A counter keeps two labels unequal; it does not make them navigable.
 *
 * <p>What the reduction is missing is not a better rule. It is that the label is <em>taken</em> from
 * the opening words rather than <em>asked for</em>. So once a conversation has had one full exchange
 * there is something to ask about, and this asks: one call, one name, kept for the rest of the
 * process. The reduction stays exactly where it was designed to be - the fallback for a conversation
 * that has not been named yet, could not be named, or arrived before the model was reachable.
 *
 * <h2>Why the call bypasses the agent</h2>
 *
 * <p>{@code Agent} routes every prompt through the conversation's own memory, so naming a thread
 * through it would write the naming question and its answer into the thread being named - the reader
 * would find a question they never asked in their own transcript. The model is therefore called
 * directly, with no memory advisor, no tools and no system prompt: the naming exchange exists only
 * for the duration of the call and is not part of any conversation.
 *
 * <h2>Boundary</h2>
 *
 * <p>Everything crossing this class is hostile until proved otherwise. What goes out is a bounded
 * excerpt of a chat message. What comes back is model output that is about to become a row label and
 * a {@code title} attribute, so it is not escaped after the fact - it is admitted only if its whole
 * shape is on an allow-list: one line, bounded length, bounded word count, and no character outside
 * a small set that cannot open a tag, an entity or an attribute. Anything else is discarded and the
 * derived title stands. The failure mode is always "the name did not change", never "the name is
 * whatever the model said".
 *
 * <h2>What this cannot do</h2>
 *
 * <p>Names live in this process and nowhere else. The chat store records no title - that is the
 * limitation {@link StoredConversationDirectory} documents - so a restart forgets every name and the
 * derivation takes over again until each conversation is named a second time. Writing them somewhere
 * durable is the store's job, and inventing a side file to hold them would put the sidebar's idea of
 * a conversation's name out of step with the store's for as long as the two disagreed.
 */
@Component
class ConversationNamer {

    /**
     * Where a failed naming attempt goes. Naming is best effort and never surfaces to the reader -
     * a conversation that could not be named keeps its derived title - so nothing about why the
     * model was unreachable, refused or answered unusably survives anywhere but here.
     */
    private static final Logger log = LoggerFactory.getLogger(ConversationNamer.class);

    /**
     * The longest name that will be accepted from the model.
     *
     * <p>Well inside the column's own limit, because a name that has to be truncated to be drawn has
     * not solved the problem the truncation caused.
     */
    static final int MAX_NAME = 60;

    /** More words than this and it is a sentence about the conversation, not a name for it. */
    private static final int MAX_WORDS = 8;

    /** How much of the exchange is worth sending. Past this the model is reading, not naming. */
    private static final int MAX_EXCERPT = 400;

    /**
     * How many conversations are remembered. Keyed by a value that ultimately arrives from outside,
     * so it is capped for the same reason {@link ConversationActivity} caps its own map.
     */
    private static final int CAPACITY = 512;

    /**
     * Naming calls allowed to be outstanding at once.
     *
     * <p>The sidebar asks for a name for every conversation it draws that has not got one, which on
     * a first render of a large store is every row at once. This is what turns that into a trickle:
     * requests past the limit are dropped rather than queued, the derived title stands for those
     * rows, and the next render asks again.
     */
    private static final int MAX_IN_FLIGHT = 4;

    /**
     * How many taken names are quoted back to the model on a retry.
     *
     * <p>Bounded because the list is unbounded model output being sent back to a model: a store of
     * two hundred conversations must not turn a retry prompt into two hundred lines of history.
     */
    private static final int MAX_QUOTED_NAMES = 8;

    /** Recorded against a conversation the model was asked about and declined to name usably. */
    private static final String DECLINED = "";

    /**
     * The whole of what a name may look like.
     *
     * <p>An allow-list by shape rather than an escape, because this string is about to be written
     * into a {@code title} attribute as well as a text node: nothing here can open a tag, close an
     * attribute, start an entity or introduce a second line, so there is no context it has to be
     * re-escaped for. Anything outside it is not a name that needs cleaning up - it is a response
     * that was not a name.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "[\\p{L}\\p{N}][\\p{L}\\p{N} ,.'&/+()\\[\\]#:_-]{2," + (MAX_NAME - 1) + "}");

    /**
     * Any run of blanks, flattened to a single space before the shape check runs. A model that
     * answers on two lines has still answered with a name, and normalising first is what lets
     * {@link #SHAPE} forbid line breaks outright rather than having to tolerate them.
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Models like to wrap a one-line answer in quotation marks, and that is not part of the name. */
    private static final Pattern WRAPPING_QUOTES = Pattern.compile("^[\"'`\\u2018\\u2019\\u201c\\u201d]+"
            + "|[\"'`\\u2018\\u2019\\u201c\\u201d]+$");

    /**
     * Sentence-ending punctuation a model leaves on a name it wrote as a sentence. Stripped rather
     * than rejected: the words are a usable name and only the full stop makes it read as prose in a
     * column of headings, so the cheap repair is better than losing the answer.
     */
    private static final Pattern TRAILING_STOP = Pattern.compile("[.!?:;,\\s]+$");

    /**
     * The whole instruction, and deliberately short.
     *
     * <p>It asks for the subject rather than for a summary, because a summary of the answer names
     * what the model said and two runs of the same question say much the same thing. It also states
     * the output format twice - once as a rule and once as a prohibition - which is what stops a
     * chatty model prefixing "Sure, here is a title:" and losing the whole response to the shape
     * check below.
     */
    private static final String INSTRUCTION = """
            Name this conversation for a list of conversations.

            Reply with the name and nothing else: two to six words, no quotation marks, no trailing \
            full stop, no explanation. Name the specific subject that was discussed, not the kind of \
            request it was.

            First message:
            %s

            First reply:
            %s""";

    /**
     * What is added when the model has just named this conversation the same as another one.
     *
     * <p>The model names each conversation on its own and cannot know what the others are called,
     * so a collision is not a mistake it made - it is a fact only this class holds. Measured on the
     * live model: five of six threads opened with an identical question came back with an identical
     * name, which is the whole defect this component exists to fix reappearing one layer up.
     */
    private static final String RETRY = """


            These names are already in use for other conversations, so do not reply with any of \
            them. Name something specific to this one instead.
            %s""";

    /**
     * The model, resolved lazily on each attempt rather than injected directly.
     *
     * <p>An {@link ObjectProvider} because a chat model need not be configured at all: JavaClaw runs
     * with no provider set up, and a hard dependency here would stop the whole application starting
     * over a feature whose absence is meant to be invisible. Resolving per attempt also means a
     * provider configured after startup is picked up without a restart.
     */
    private final ObjectProvider<ChatModel> chatModel;

    /**
     * Conversation id to its name, with {@link #DECLINED} for one that has been asked about and has
     * no usable answer. Both outcomes are recorded, because "asked and got nothing" has to stop the
     * next render asking again.
     */
    private final Map<String, String> names = new ConcurrentHashMap<>();

    /**
     * How many naming calls are outstanding, checked against {@link #MAX_IN_FLIGHT} before another
     * is started. The executor's queue is unbounded, so without this counter one render of a large
     * store would enqueue a model call per conversation and they would all eventually run; the
     * bound has to be applied at submission time, which is why it is a counter and not a queue size.
     *
     * <p>Read and written from the UI thread that submits and from the naming threads that finish,
     * which is why it is atomic.
     */
    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * Who to tell when a conversation acquires a name. Copy-on-write because registration and
     * removal happen on UI threads while the list is iterated from a naming thread, and the list is
     * tiny and read far more often than it changes - one entry per attached sidebar.
     */
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * Two threads, off the request path.
     *
     * <p>Naming is a blocking call to a model that may take seconds, and it happens while a sidebar
     * is being drawn - so it cannot be on the thread drawing it. Two rather than one so a slow
     * conversation does not hold up the rest; bounded rather than elastic because the point of a
     * bound is that a large store cannot turn one page load into a hundred model calls. Daemon
     * threads, so a naming call in flight cannot keep the JVM alive at shutdown.
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "jc-conversation-namer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Builds the single application-scoped namer. Spring supplies the provider.
     *
     * <p>Nothing is started here. The executor's threads are created on demand, so an application
     * where nobody opens the UI never spins one up, and an application with no chat model
     * configured resolves the provider to nothing at the first attempt rather than failing to start.
     *
     * @param chatModel the provider for the configured chat model; may resolve to nothing, which is
     *     a supported configuration and not an error
     */
    ConversationNamer(ObjectProvider<ChatModel> chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * The name the model gave this conversation, if it has been asked and answered usably.
     *
     * @param conversationId conversation to look up; null and unknown are tolerated
     * @return the recorded name, or empty when the caller should use its own derivation
     */
    Optional<String> nameOf(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        String name = names.get(conversationId);
        return name == null || name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /**
     * Asks for a name, at most once per conversation per process.
     *
     * <p>Returns immediately. The caller keeps drawing whatever it was going to draw; if a name
     * arrives, the listeners registered through {@link #onNamed} are told and the column redraws
     * itself. There is deliberately no way to wait for one - a sidebar that blocked on the model
     * would be a sidebar that does not paint while the model is slow.
     *
     * @param conversationId the conversation to name; refused unless well formed
     * @param question       the message that opened it
     * @param answer         the reply it drew, which is what makes this an exchange worth naming
     */
    void requestName(String conversationId, String question, String answer) {
        if (!ConversationIds.isWellFormed(conversationId)
                || question == null || question.isBlank()
                || answer == null || answer.isBlank()) {
            return;
        }
        // A conversation is named once. Re-naming it on a later exchange would move a row's label
        // under a reader who is using that label to find the row.
        if (names.putIfAbsent(conversationId, DECLINED) != null) {
            return;
        }
        if (names.size() > CAPACITY || inFlight.get() >= MAX_IN_FLIGHT) {
            // Dropped rather than queued, and the marker is withdrawn so a later render can retry.
            names.remove(conversationId, DECLINED);
            return;
        }
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> nameNow(conversationId, question, answer));
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            names.remove(conversationId, DECLINED);
        }
    }

    /**
     * Told whenever a conversation has just acquired a name.
     *
     * <p>The sidebar is drawn before any of this has happened, so without a way to hear about it the
     * names would only appear on the reader's next click. Listeners are called on the naming thread;
     * a UI listener is responsible for getting itself back onto its own thread.
     *
     * @param listener run once per successful naming
     * @return the registration to remove it with, which a component must do when it detaches
     */
    Registration onNamed(Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * One naming call, on the naming thread.
     *
     * <p>Every failure lands in the same place: the conversation stays marked as asked-about with no
     * name, and the caller's own derivation keeps the row labelled. A model that is missing, slow,
     * broken or off-format is therefore not an error the reader ever sees.
     *
     * @param conversationId the conversation being named
     * @param question the opening question, already excerpted
     * @param answer the first reply, already excerpted
     */
    private void nameNow(String conversationId, String question, String answer) {
        try {
            ChatModel model = chatModel.getIfUnique();
            if (model == null) {
                return;
            }
            String prompt = INSTRUCTION.formatted(excerpt(question), excerpt(answer));
            Optional<String> first = usableName(ask(model, prompt));
            if (first.isPresent() && claim(conversationId, first.get())) {
                return;
            }
            // Only now, and only for the thread that collided. Asking twice for every conversation
            // would double the cost of drawing a column to solve a problem most columns do not have.
            Optional<String> second = usableName(ask(model, prompt + RETRY.formatted(takenNames())));
            Optional<String> chosen = second.isPresent() ? second : first;
            // A name that collides again is still kept. Two threads that really are the same
            // question are allowed to share a name, and what separates them then is the column's
            // occurrence marker - which is a far better outcome than six rows of derived echo.
            chosen.ifPresent(name -> record(conversationId, name));
        } catch (RuntimeException e) {
            log.debug("Could not name conversation {}; keeping the derived title", conversationId, e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * One blocking round trip to the model.
     *
     * <p>Deliberately thin, and deliberately does not judge what came back: the shape rules live in
     * {@link #usableName(String)} so that both the first attempt and the retry are held to the same
     * standard by the same code. A null response is passed through as null rather than turned into
     * an empty string, because "the model returned nothing" and "the model returned blank text" are
     * the same outcome to the caller and neither is a name.
     *
     * <p>Runs on a naming thread, never on the Vaadin UI thread.
     *
     * @param model the resolved chat model
     * @param prompt the whole instruction, already formatted
     * @return the model's text, or null when there was no response to read
     */
    private static String ask(ChatModel model, String prompt) {
        ChatResponse response = model.call(new Prompt(prompt));
        return response == null ? null : response.getResult().getOutput().getText();
    }

    /**
     * Takes a name if no other conversation already has it.
     *
     * <p>Synchronised because two naming threads can arrive at the same name at the same moment,
     * and a check followed by an unsynchronised write would let both through - which is precisely
     * the collision this exists to catch.
     *
     * @param conversationId the conversation claiming the name
     * @param name the candidate, already shape-checked
     * @return true when the name was free and is now this conversation's
     */
    private synchronized boolean claim(String conversationId, String name) {
        for (Map.Entry<String, String> entry : names.entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue()) && !entry.getKey().equals(conversationId)) {
                return false;
            }
        }
        record(conversationId, name);
        return true;
    }

    /**
     * The names already handed out, as a list the instruction can quote back at the model.
     *
     * @return the names, one per line and bounded by {@link #MAX_QUOTED_NAMES}, or an empty string
     *     when nothing has been named yet - in which case the caller must not append the retry at all
     */
    private synchronized String takenNames() {
        return names.values().stream()
                .filter(name -> !name.isEmpty())
                .distinct()
                .limit(MAX_QUOTED_NAMES)
                .map(name -> "- " + name)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Records a name and tells whoever is drawing rows.
     *
     * @param conversationId the conversation being named
     * @param name the accepted name, or {@link #DECLINED} to record that asking produced nothing
     */
    private void record(String conversationId, String name) {
        names.put(conversationId, name);
        listeners.forEach(ConversationNamer::notifyQuietly);
    }

    /**
     * A listener that throws is a listener, not a naming failure.
     *
     * <p>One detached UI must not stop the others hearing about the name, and must not lose the name
     * that was already recorded - so the throw stops here rather than unwinding into
     * {@link #record(String, String)}, which has already written the name by the time listeners run.
     *
     * @param listener one registered callback, run on the thread that produced the name
     */
    private static void notifyQuietly(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            log.debug("A conversation-name listener failed", e);
        }
    }

    /**
     * The model's response, if the whole of it is shaped like a name.
     *
     * <p>Deliberately all-or-nothing. Trimming a bad response into a good one means deciding which
     * part of it was the name, and a class that guesses that is a class that will one day put a
     * fragment of a refusal in the navigation column.
     *
     * @param candidate raw model output, possibly null, empty, multi-line or nothing like a name
     * @return the name to record, or empty to leave the derived title in place
     */
    static Optional<String> usableName(String candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        String flat = WHITESPACE.matcher(candidate).replaceAll(" ").strip();
        // Bounded before any pattern runs over it: the response is not under this application's
        // control and the patterns below should never see a megabyte of it.
        if (flat.isEmpty() || flat.length() > MAX_EXCERPT) {
            return Optional.empty();
        }
        flat = WRAPPING_QUOTES.matcher(flat).replaceAll("");
        flat = TRAILING_STOP.matcher(flat).replaceFirst("").strip();
        if (flat.length() > MAX_NAME || flat.split(" ").length > MAX_WORDS) {
            return Optional.empty();
        }
        if (!SHAPE.matcher(flat).matches()) {
            return Optional.empty();
        }
        return Optional.of(flat);
    }

    /**
     * As much of a message as is worth sending, on one line.
     *
     * @param text the message, never null
     * @return the message flattened to one line and cut to {@link #MAX_EXCERPT}; never null, and
     *     empty when the message was blank
     */
    private static String excerpt(String text) {
        String flat = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return flat.length() > MAX_EXCERPT ? flat.substring(0, MAX_EXCERPT) : flat;
    }

    /**
     * Stops the naming threads when the application context shuts down.
     *
     * <p>{@code shutdownNow} rather than a graceful {@code shutdown} and await: an outstanding
     * naming call is a blocking round trip to a model that may take seconds and whose only product
     * is a nicer row label, so waiting on it would delay shutdown for something nobody is going to
     * see. The names already recorded are lost with the process either way - see the class note.
     */
    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
