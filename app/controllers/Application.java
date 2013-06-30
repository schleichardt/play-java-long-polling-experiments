package controllers;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.AskTimeoutException;
import play.Logger;
import play.libs.Akka;
import play.libs.F.Function;
import play.mvc.Controller;
import play.mvc.Result;
import views.html.index;

import java.util.*;

import static akka.pattern.Patterns.ask;

public class Application extends Controller {

    private final static ActorRef myActor = Akka.system().actorOf(new Props(XyzActor.class), "dissy");

    public static Result index() {
        return ok(index.render("Your new application is ready."));
    }

    public static Result poll(int id) {
        final AsyncResult async = async(
                Akka.asPromise(ask(myActor, new Getter(id), 10000)).map(
                        new Function<Object, Result>() {
                            public Result apply(Object response) {
                                Setter converted = (Setter) response;
                                final int id = converted.getId();
                                final int value = converted.getValue();
                                return ok("polled(" + id + ", " + value + ")");
                            }
                        }
                ).recover(new Function<Throwable, Result>() {
                    @Override
                    public Result apply(Throwable throwable) throws Throwable {
                        if (throwable instanceof AskTimeoutException) {
                            return status(NOT_MODIFIED);
                        } else {
                            return internalServerError();
                        }
                    }
                })
        );
        Logger.debug("produced async result for " + id);
        return async;
    }

    public static Result get(int id) {
        return ok("get(" + id + ")");
    }

    public static Result set(int id, int value) {
        myActor.tell(new Setter(id, value));
        return ok("set(" + id + ", " + value + ")");
    }


    public static class Setter {
        private final int id;
        private final int value;

        public Setter(int id, int value) {
            this.id = id;
            this.value = value;
        }

        public int getId() {
            return id;
        }

        public int getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "Setter{" +
                    "id=" + id +
                    ", value=" + value +
                    '}';
        }
    }

    public static class Getter {
        private final int id;

        public Getter(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        @Override
        public String toString() {
            return "Getter{" +
                    "id=" + id +
                    '}';
        }
    }

    public static class XyzActor extends UntypedActor {
        LoggingAdapter log = Logging.getLogger(getContext().system(), this);

        private Map<Integer, Set<ActorRef>> listener = new HashMap<Integer, Set<ActorRef>>();


        public void onReceive(Object message) throws Exception {
            final String uuid = UUID.randomUUID().toString();
            Logger.info(uuid + " received: " + message);
            if (message instanceof Getter) {
                Getter converted = (Getter) message;

                Set<ActorRef> actorRefs = listener.get(converted.getId());
                if (actorRefs == null) {
                    actorRefs = new HashSet<>();
                }
                actorRefs.add(getSender());
                listener.put(converted.getId(), actorRefs);
                Logger.info(getSender() + "requests " + converted.getId() + ", size of actorRefs=" + actorRefs.size() + ", map=" + listener.toString());

            } else if (message instanceof Setter) {
                Setter converted = (Setter) message;
                final Set<ActorRef> actorRefs = listener.get(converted.getId());

                if (actorRefs != null) {
                    for (ActorRef actorRef : actorRefs) {
                        actorRef.tell(converted, getSelf());
                        Logger.info("telling " + converted + " to " + actorRef);
                    }
                    listener.remove(converted.getId());
                    Logger.info("removing actors for " + converted.getId() + ", map=" + listener.toString());
                }
            } else {
                Logger.info("unhandled");
                unhandled(message);
            }

            Logger.info(uuid + " processed: " + message);

        }
    }

}