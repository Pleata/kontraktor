package org.nustaq.kontraktor.remoting;

import org.nustaq.kontraktor.Actor;
import org.nustaq.kontraktor.Actors;
import org.nustaq.kontraktor.Callback;
import org.nustaq.kontraktor.Future;
import org.nustaq.kontraktor.impl.*;
import org.nustaq.kontraktor.remoting.tcp.TCPActorClient;
import org.nustaq.serialization.FSTConfiguration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by ruedi on 08.08.14.
 *
 * fixme: handle stop of published actor (best by talking back in case a message is received on a
 * stopped published actor).
 */
public class RemoteRefRegistry {

    protected FSTConfiguration conf = FSTConfiguration.createDefaultConfiguration();

    RemoteScheduler scheduler = new RemoteScheduler(); // unstarted thread dummy

    // holds published actors, futures and callbacks of this process
    AtomicInteger actorIdCount = new AtomicInteger(0);
    ConcurrentHashMap<Integer, Object> publishedActorMapping = new ConcurrentHashMap<>();
    ConcurrentHashMap<Object, Integer> publishedActorMappingReverse = new ConcurrentHashMap<>();

    BackOffStrategy backOffStrategy = new BackOffStrategy();

    // have disabled dispacther thread
    ConcurrentLinkedQueue<Actor> remoteActors = new ConcurrentLinkedQueue<>();
    ConcurrentHashMap<Integer,Actor> remoteActorSet = new ConcurrentHashMap<>();

    protected ThreadLocal<ObjectSocket> currentChannel = new ThreadLocal<>();
    protected volatile boolean terminated = false;

    public RemoteRefRegistry() {
        conf.registerSerializer(Actor.class,new ActorRefSerializer(this),true);
        conf.registerSerializer(CallbackWrapper.class, new CallbackRefSerializer(this), true);
        conf.registerClass(RemoteCallEntry.class);
    }

    public Actor getPublishedActor(int id) {
        return (Actor) publishedActorMapping.get(id);
    }

    public Callback getPublishedCallback(int id) {
        return (Callback) publishedActorMapping.get(id);
    }

    public RemoteScheduler getScheduler() {
        return scheduler;
    }

    public ConcurrentLinkedQueue<Actor> getRemoteActors() {
        return remoteActors;
    }

    public boolean isTerminated() {
        return terminated;
    }

    public void setTerminated(boolean terminated) {
        this.terminated = terminated;
    }

    public int publishActor(Actor act) {
        Integer integer = publishedActorMappingReverse.get(act.getActorRef());
        if ( integer == null ) {
            integer = actorIdCount.incrementAndGet();
            publishedActorMapping.put(integer, act.getActorRef());
            publishedActorMappingReverse.put(act.getActorRef(), integer);
        }
        return integer;
    }

    public void unpublishActor(Actor act) {
        Integer integer = publishedActorMappingReverse.get(act.getActorRef());
        if ( integer != null ) {
            publishedActorMapping.remove(integer);
            publishedActorMappingReverse.remove(act.getActorRef());
        }
    }

    public int registerPublishedCallback(Callback cb) {
        Integer integer = publishedActorMappingReverse.get(cb);
        if ( integer == null ) {
            integer = actorIdCount.incrementAndGet();
            publishedActorMapping.put(integer, cb);
            publishedActorMappingReverse.put(cb, integer);
        }
        return integer;
    }

    public void removePublishedObject(int receiverKey) {
        Object remove = publishedActorMapping.remove(receiverKey);
        if ( remove != null ) {
            publishedActorMappingReverse.remove(remove);
        }
    }

    public void registerRemoteRefDirect(Actor act) {
        act = act.getActorRef();
        remoteActorSet.put(act.__remoteId,act);
        remoteActors.add(act);
        act.__addStopHandler((actor, err) -> {
            remoteRefStopped((Actor) actor);
        });
    }

    public Actor registerRemoteActorRef(Class actorClazz, int remoteId, Object client) {
        Actor actorRef = remoteActorSet.get(remoteId);
        if ( actorRef == null ) {
            Actor res = Actors.AsActor(actorClazz, getScheduler());
            res.__remoteId = remoteId;
            remoteActorSet.put(remoteId,res);
            remoteActors.add(res);
            res.__addStopHandler( (actor,err) -> {
                remoteRefStopped((Actor) actor);
            });
            return res;
        }
        return actorRef;
    }

    /**
     * warning: MThreaded
     * @param actor
     */
    protected void remoteRefStopped(Actor actor) {
        removeRemoteActor(actor);
        actor.getActorRef().__stopped = true;
        actor.getActor().__stopped = true;
    }

    protected void stopRemoteRefs() {
        new ArrayList<>(remoteActors).forEach( (actor) -> {
            //don't call remoteRefStopped here as its designed to be overridden
            removeRemoteActor(actor);
            actor.getActorRef().__stopped = true;
            actor.getActor().__stopped = true;
        });
    }

    private void removeRemoteActor(Actor act) {
        remoteActorSet.remove(act.__remoteId);
        remoteActors.remove(act);
    }

    protected void sendLoop(ObjectSocket channel) throws IOException {
        int count = 0;
        while (!isTerminated()) {
            if ( singleSendLoop(channel) ) {
                count = 0;
            }
            backOffStrategy.yield(count++);
        }
    }

    protected void receiveLoop(ObjectSocket channel) {
        try {
            while( !isTerminated() ) {
                // read object
                final Object response = channel.readObject();
                if (response instanceof RemoteCallEntry == false) {
                    System.out.println(response); // fixme
                    continue;
                }
                RemoteCallEntry read = (RemoteCallEntry) response;
                boolean isContinue = read.getArgs().length > 1 && Callback.CONTINUE.equals(read.getArgs()[1]);
                if ( isContinue )
                    read.getArgs()[1] = Callback.CONTINUE; // enable ==
                if (read.getQueue() == read.MAILBOX) {
                    Actor targetActor = getPublishedActor(read.getReceiverKey());
                    if (targetActor==null) {
                        System.out.println("no actor found for key "+read);
                        continue;
                    }

                    Object future = targetActor.getScheduler().enqueueCall(null, targetActor, read.getMethod(), read.getArgs());
                    if ( future instanceof Future ) {
                        ((Future) future).then( (r,e) -> {
                            try {
                                receiveCBResult(channel, read.getFutureKey(), r, e);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        });
                    }
                } else if (read.getQueue() == read.CBQ) {
                    Callback publishedCallback = getPublishedCallback(read.getReceiverKey());
                    publishedCallback.receiveResult(read.getArgs()[0],read.getArgs()[1]); // is a wrapper enqueuing in caller
                    if (!isContinue)
                        removePublishedObject(read.getReceiverKey());
                }
            }
        } catch (Exception e) {
            System.out.println(e);
//            e.printStackTrace();
        }
    }

    /**
     * poll remote actor proxies and send. return true if there was at least one method
     * @param chan
     */
    public boolean singleSendLoop(ObjectSocket chan) throws IOException {
        boolean res = false;
        int sumQueued = 0;
        ArrayList<Actor> toRemove = null;
        for (Iterator<Actor> iterator = remoteActors.iterator(); iterator.hasNext(); ) {
            Actor remoteActor = iterator.next();
            CallEntry ce = (CallEntry) remoteActor.__mailbox.poll();
            if ( ce != null) {
                if ( ce.getMethod().getName().equals("$stop") ) {
                    new Thread( () -> {
                        try {
                            remoteActor.getActor().$stop();
                        } catch (ActorStoppedException ex) {}
                    }, "stopper thread").start();
                } else {
                    sumQueued += remoteActor.__mailbox.size();
                    int futId = 0;
                    if (ce.hasFutureResult()) {
                        futId = registerPublishedCallback(ce.getFutureCB());
                    }
                    try {
                        RemoteCallEntry rce = new RemoteCallEntry(futId, remoteActor.__remoteId, ce.getMethod().getName(), ce.getArgs());
                        rce.setQueue(rce.MAILBOX);
                        writeObject(chan, rce);
                        res = true;
                    } catch (Exception ex) {
                        chan.setLastError(ex);
                        if (toRemove == null)
                            toRemove = new ArrayList();
                        toRemove.add(remoteActor);
                        remoteActor.$stop();
                        System.out.println("connection closed");
                        ex.printStackTrace();
                        break;
                    }
                }
            }
        }
        if (toRemove!=null) {
            toRemove.forEach( (act) -> removeRemoteActor(act) );
        }
        if ( sumQueued < 100 )
        {
            chan.flush();
        }
        return res;
    }

    protected void writeObject(ObjectSocket chan, RemoteCallEntry rce) throws Exception {
        chan.writeObject(rce);
    }

    public void receiveCBResult(ObjectSocket chan, int id, Object result, Object error) throws Exception {
        RemoteCallEntry rce = new RemoteCallEntry(0, id, null, new Object[] {result,error});
        rce.setQueue(rce.CBQ);
        writeObject(chan, rce);
    }
}