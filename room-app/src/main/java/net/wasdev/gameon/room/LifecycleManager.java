/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package net.wasdev.gameon.room;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import javax.enterprise.context.ApplicationScoped;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.websocket.Endpoint;
import javax.websocket.Session;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpointConfig;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import net.wasdev.gameon.room.engine.Engine;
import net.wasdev.gameon.room.engine.Room;

/**
 * Manages the registration of all rooms in the Engine with the concierge
 */
@ApplicationScoped
public class LifecycleManager implements ServerApplicationConfig {

    private String mapLocation = null;
    private String registrationSecret;

    Engine e = Engine.getEngine();

    public static class SessionRoomResponseProcessor
            implements net.wasdev.gameon.room.engine.Room.RoomResponseProcessor {
        AtomicInteger counter = new AtomicInteger(0);

        private void generateEvent(Session session, JsonObject content, String userID, boolean selfOnly, int bookmark)
                throws IOException {
            JsonObjectBuilder response = Json.createObjectBuilder();
            response.add("type", "event");
            response.add("content", content);
            response.add("bookmark", bookmark);

            String msg = "player," + (selfOnly ? userID : "*") + "," + response.build().toString();
            System.out.println("ROOM(PE): sending to session " + session.getId() + " message:" + msg);
            session.getBasicRemote().sendText(msg);
        }

        @Override
        public void playerEvent(String senderId, String selfMessage, String othersMessage) {
            // System.out.println("Player message :: from("+senderId+")
            // onlyForSelf("+String.valueOf(selfMessage)+")
            // others("+String.valueOf(othersMessage)+")");
            JsonObjectBuilder content = Json.createObjectBuilder();
            boolean selfOnly = true;
            if (othersMessage != null && othersMessage.length() > 0) {
                content.add("*", othersMessage);
                selfOnly = false;
            }
            if (selfMessage != null && selfMessage.length() > 0) {
                content.add(senderId, selfMessage);
            }
            JsonObject json = content.build();
            int count = counter.incrementAndGet();
            for (Session s : activeSessions) {
                try {
                    generateEvent(s, json, senderId, selfOnly, count);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        private void generateRoomEvent(Session session, JsonObject content, int bookmark) throws IOException {
            JsonObjectBuilder response = Json.createObjectBuilder();
            response.add("type", "event");
            response.add("content", content);
            response.add("bookmark", bookmark);

            String msg = "player,*," + response.build().toString();
            System.out.println("ROOM(RE): sending to session " + session.getId() + " message:" + msg);

            session.getBasicRemote().sendText(msg);
        }

        @Override
        public void roomEvent(String s) {
            // System.out.println("Message sent to everyone :: "+s);
            JsonObjectBuilder content = Json.createObjectBuilder();
            content.add("*", s);
            JsonObject json = content.build();
            int count = counter.incrementAndGet();
            for (Session session : activeSessions) {
                try {
                    generateRoomEvent(session, json, count);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        public void chatEvent(String username, String msg) {
            JsonObjectBuilder content = Json.createObjectBuilder();
            content.add("type", "chat");
            content.add("username", username);
            content.add("content", msg);
            content.add("bookmark", counter.incrementAndGet());
            JsonObject json = content.build();
            for (Session session : activeSessions) {
                try {
                    String cmsg = "player,*," + json.toString();
                    System.out.println("ROOM(CE): sending to session " + session.getId() + " message:" + cmsg);

                    session.getBasicRemote().sendText(cmsg);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        @Override
        public void locationEvent(String senderId, String roomId, String roomName, String roomDescription, Map<String,String> exits,
                List<String> objects, List<String> inventory, Map<String,String> commands) {
            JsonObjectBuilder content = Json.createObjectBuilder();
            content.add("type", "location");
            content.add("name", roomId);
            content.add("fullName", roomName);
            content.add("description", roomDescription);
            
            JsonObjectBuilder exitJson = Json.createObjectBuilder();
            for (Entry<String, String> e : exits.entrySet()) {
                exitJson.add(e.getKey().toUpperCase(), e.getValue());
            }
            content.add("exits", exitJson.build());
            
            JsonObjectBuilder commandJson = Json.createObjectBuilder();
            for (Entry<String, String> c : commands.entrySet()) {
                commandJson.add(c.getKey().toUpperCase(), c.getValue());
            }
            content.add("commands", commandJson.build());
            
            JsonArrayBuilder inv = Json.createArrayBuilder();
            for (String i : inventory) {
                inv.add(i);
            }
            content.add("pockets", inv.build());
            
            JsonArrayBuilder objs = Json.createArrayBuilder();
            for (String o : objects) {
                objs.add(o);
            }
            content.add("objects", objs.build());
            content.add("bookmark", counter.incrementAndGet());

            JsonObject json = content.build();
            for (Session session : activeSessions) {
                try {
                    String lmsg = "player," + senderId + "," + json.toString();
                    System.out.println("ROOM(LE): sending to session " + session.getId() + " message:" + lmsg);
                    session.getBasicRemote().sendText(lmsg);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        @Override
        public void exitEvent(String senderId, String message, String exitID, String exitJson) {
            JsonObjectBuilder content = Json.createObjectBuilder();
            content.add("type", "exit");
            content.add("exitId", exitID);
            content.add("content", message);
            content.add("bookmark", counter.incrementAndGet());
            JsonObject json = content.build();
            for (Session session : activeSessions) {
                try {
                    String emsg = "playerLocation," + senderId + "," + json.toString();
                    System.out.println("ROOM(EE): sending to session " + session.getId() + " message:" + emsg);
                    session.getBasicRemote().sendText(emsg);
                } catch (IOException io) {
                    throw new RuntimeException(io);
                }
            }
        }

        Collection<Session> activeSessions = new CopyOnWriteArraySet<Session>();

        public void addSession(Session s) {
            activeSessions.add(s);
        }

        public void removeSession(Session s) {
            activeSessions.remove(s);
        }
    }

    private void getConfig() throws ServletException {
        try {
            registrationSecret = (String) new InitialContext().lookup("registrationSecret");
        } catch (NamingException e) {
        }
        if (registrationSecret == null) {
            throw new ServletException("registrationSecret was not found, check server.xml/server.env");
        }
    }

    private static class RoomWSConfig extends ServerEndpointConfig.Configurator {
        private final Room room;
        private final SessionRoomResponseProcessor srrp;

        public RoomWSConfig(Room room, SessionRoomResponseProcessor srrp) {
            this.room = room;
            this.srrp = srrp;
            this.room.setRoomResponseProcessor(srrp);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass) {
            RoomWS r = new RoomWS(this.room, this.srrp);
            return (T) r;
        }
    }

    private Set<ServerEndpointConfig> registerRooms(Collection<Room> rooms) {

        Set<ServerEndpointConfig> endpoints = new HashSet<ServerEndpointConfig>();
        for (Room room : rooms) {
            
            RoomRegistrationHandler roomRegistration = new RoomRegistrationHandler(room, registrationSecret);
            try{
                roomRegistration.performRegistration();
            }catch(Exception e){
                System.out.println("Room registration FAILED : "+e.getMessage());
                e.printStackTrace();
            }
            
            //now regardless of our registration, open our websocket.
            SessionRoomResponseProcessor srrp = new SessionRoomResponseProcessor();
            ServerEndpointConfig.Configurator config = new RoomWSConfig(room, srrp);

            endpoints.add(ServerEndpointConfig.Builder.create(RoomWS.class, "/ws/" + room.getRoomId())
                    .configurator(config).build());
        }

        return endpoints;
    }

    @Override
    public Set<ServerEndpointConfig> getEndpointConfigs(Set<Class<? extends Endpoint>> endpointClasses) {
        try {
            if(registrationSecret==null)
                getConfig();
            return registerRooms(e.getRooms());
        } catch (ServletException e) {
            System.err.println("Error building endpoint configs for ro");
            e.printStackTrace();
            throw new RuntimeException(e);
        }

    }

    @Override
    public Set<Class<?>> getAnnotatedEndpointClasses(Set<Class<?>> scanned) {
        return null;
    }

}
