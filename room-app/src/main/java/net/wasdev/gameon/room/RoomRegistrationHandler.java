package net.wasdev.gameon.room;

import java.io.StringReader;
import java.net.ConnectException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.enterprise.concurrent.ManagedScheduledExecutorService;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.naming.InitialContext;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import net.wasdev.gameon.room.engine.Room;
import net.wasdev.gameon.room.engine.meta.DoorDesc;
import net.wasdev.gameon.room.engine.meta.ExitDesc;

public class RoomRegistrationHandler {

    
    private final String secret;
    private final String endPoint;
    private final String mapLocation;
    private final Room room;
    AtomicBoolean handling503 = new AtomicBoolean(false);
    
    
    RoomRegistrationHandler(Room room, String secret){
        this.room=room;
        this.secret=secret;
        
        endPoint = System.getProperty(Constants.ENV_ROOM_SVC, System.getenv(Constants.ENV_ROOM_SVC));
        if (endPoint == null) {
            throw new RuntimeException("The location for the room service cold not be "
                    + "found in a system property or environment variable named : " + Constants.ENV_ROOM_SVC);
        }
        mapLocation = System.getProperty(Constants.ENV_MAP_SVC, System.getenv(Constants.ENV_MAP_SVC));
        if (mapLocation == null) {
            throw new RuntimeException("The location for the map service cold not be "
                    + "found in a system property or environment variable named : " + Constants.ENV_MAP_SVC);
        }
    }
    
    private static class RegistrationResult {
        enum Type { NOT_REGISTERED, REGISTERED, SERVICE_UNAVAILABLE };
        public Type type;
        public JsonObject registeredObject;
    }
    
    /**
     * Obtain current registration for this room
     * @param roomId
     * @return
     */
    private RegistrationResult checkExistingRegistration() throws Exception {
        RegistrationResult result = new RegistrationResult();
        try {
            Client queryClient = ClientBuilder.newClient();

            // add our shared secret so all our queries come from the
            // game-on.org id
            queryClient.register(new GameOnHeaderAuthFilter(Constants.GAMEON_ID, secret));

            // create the jax-rs 2.0 client
            WebTarget queryRoot = queryClient.target(mapLocation);
            // add the lookup arg for this room..
            WebTarget target = queryRoot.queryParam("owner", Constants.GAMEON_ID).queryParam("name", room.getRoomId());
            Response r = null;

            r = target.request(MediaType.APPLICATION_JSON).get(); // .accept(MediaType.APPLICATION_JSON).get();
            int code = r.getStatusInfo().getStatusCode();
            switch (code) {
                case 204: {
                    // room is unknown to map
                    result.type = RegistrationResult.Type.NOT_REGISTERED;
                    return result;
                }
                case 200: {
                    // request succeeded.. we need to parse the result into a JsonObject..
                    // query url always returns an array, so we need to reach in to obtain our 
                    // hit. There should only ever be the one, becase we searched by owner and 
                    // name, and rooms should be unique by owner & name;
                    String respString = r.readEntity(String.class);
                    JsonReader reader = Json.createReader(new StringReader(respString));
                    JsonArray resp = reader.readArray();              
                    JsonObject queryResponse = resp.getJsonObject(0);
                    
                    //get the id for our already-registered room.
                    String id = queryResponse.getString("_id");
                    
                    // now we have our id.. make a new request to get our exit wirings.. 
                    queryClient = ClientBuilder.newClient();
                    queryClient.register(new GameOnHeaderAuthFilter(Constants.GAMEON_ID, secret));
                    WebTarget lookup = queryClient.target(mapLocation);
                    Invocation.Builder builder = lookup.path("{roomId}").resolveTemplate("roomId", id).request(MediaType.APPLICATION_JSON);
                    Response response = builder.get();
                    respString = response.readEntity(String.class);    
                    
                    System.out.println("EXISTING_INFO("+Constants.GAMEON_ID+")("+room.getRoomId()+") : "+respString);
                    
                    reader = Json.createReader(new StringReader(respString));                    
                    queryResponse = reader.readObject();
                                        
                    //save the full response with exit info into the result var.
                    result.type = RegistrationResult.Type.REGISTERED;
                    result.registeredObject = queryResponse;
                    return result;
                }
                case 404: {
                    // fall through to 503.
                }
                case 503: {
                    // service was unavailable.. we need to reschedule ourselves
                    // to try again later..
                    if (handling503.compareAndSet(false, true)) {
                        handle503();
                    }
                    result.type = RegistrationResult.Type.SERVICE_UNAVAILABLE;
                    return result;
                }
                default: {
                    throw new Exception("Unknown response code from map " + code);
                }
            }
        } catch (ProcessingException e){
            if(e.getCause() instanceof ConnectException){
                if (handling503.compareAndSet(false, true)) {
                    handle503();
                }
                result.type = RegistrationResult.Type.SERVICE_UNAVAILABLE;
                return result;
            }else{
                throw e;
            }
        } catch (Exception e) {
            throw new Exception("Error querying room registration", e);
        }
    }
    
    private void handle503() throws Exception{
        try{
            System.out.println("Scheduling room "+room.getRoomId()+" to be registered via bg thread.");
            ManagedScheduledExecutorService executor;
            executor = (ManagedScheduledExecutorService) new InitialContext().lookup("concurrent/execSvc");         
            
            Thread r = new Thread(){
                public void run() {
                    try{
                        System.out.println("bg registration thread for "+room.getRoomId()+" running.");
                        if(performRegistration()){
                            executor.shutdown();
                        }
                    }catch(Exception e){
                        throw new RuntimeException("Registration Thread Fail",e);
                    }
                };
            };
            
            executor.scheduleAtFixedRate(r, 10, 10, TimeUnit.SECONDS);
        }catch(Exception e){
            throw new Exception("Error creating scheduler to handle 503 response from map",e);
        }
    }
    
    public boolean performRegistration() throws Exception{
        RegistrationResult existingRegistration = checkExistingRegistration();
        switch(existingRegistration.type){
            case REGISTERED:{
                RegistrationResult updatedRegistration = compareRoomAndUpdateIfRequired(existingRegistration.registeredObject);
                if(updatedRegistration.type == RegistrationResult.Type.REGISTERED){
                    updateRoomWithExits(updatedRegistration.registeredObject);
                }else{
                    System.out.println("Unable to update room registration for "+room.getRoomId());
                    //use old registered room exit info.
                    updateRoomWithExits(existingRegistration.registeredObject);
                }
                return true;
            }
            case NOT_REGISTERED:{
                RegistrationResult newRegistration = registerRoom();
                if(newRegistration.type == RegistrationResult.Type.REGISTERED){
                    updateRoomWithExits(newRegistration.registeredObject);
                }
                return true;
            }
            case SERVICE_UNAVAILABLE:{
                //background thread has been scheduled to re-attempt registration later.                
                return false;
            }
            default:{
                throw new RuntimeException("Unknown enum value "+existingRegistration.type.toString());
            }               
        }
    }
    
    private void updateRoomWithExits(JsonObject registeredObject) {
        JsonObject exits = registeredObject.getJsonObject("exits");
        Map<String,ExitDesc> exitMap = new HashMap<String,ExitDesc>();
        for(Entry<String, JsonValue> e : exits.entrySet()){
            try{
            JsonObject j = (JsonObject)e.getValue();
            //can be null, eg when linking back to firstroom
            JsonObject c = j.getJsonObject("connectionDetails");
            ExitDesc exit = new ExitDesc(e.getKey(), 
                    j.getString("name"), 
                    j.getString("fullName"), 
                    j.getString("door"), 
                    j.getString("_id"),
                    c!=null?c.getString("type"):null,
                    c!=null?c.getString("target"):null);
            exitMap.put(e.getKey(), exit);
            System.out.println("Added exit "+e.getKey()+" to "+room.getRoomId()+" : "+exit);
            }catch(Exception ex){
                ex.printStackTrace();
            }
        }
        room.setExits(exitMap);
    }

    private RegistrationResult compareRoomAndUpdateIfRequired(JsonObject registeredRoom) throws Exception{
        JsonObject info = registeredRoom.getJsonObject("info");
        
        boolean needsUpdate = true;
        if(   room.getRoomId().equals(info.getString("name"))
           && room.getRoomName().equals(info.getString("fullName"))
           && room.getRoomDescription().equals(info.getString("description"))
                )
        {
            //all good so far =)
            JsonObject doors = info.getJsonObject("doors");
            int count = room.getDoors().size();
            if(doors!=null && doors.size()==count){                
                for(DoorDesc door : room.getDoors()){
                    String description = doors.getString(door.direction.toString().toLowerCase());
                    if(description.equals(door.description)){
                        count--;
                    }
                }
            }else{
                System.out.println("Door count mismatch.");
            }
            //if all the doors matched.. lets check the connection details..
            if(count==0){
                JsonObject connectionDetails = info.getJsonObject("connectionDetails");
                if(connectionDetails!=null){
                    if("websocket".equals(connectionDetails.getString("type"))
                       && getEndpointForRoom().equals(connectionDetails.getString("target"))){
                        
                        //all good.. no need to update this one.
                        needsUpdate = false;
                        
                    }else{
                        System.out.println("ConnectionDetails mismatch.");
                    }
                }else{
                    System.out.println("ConnectionDetails absent.");
                }
            }else{
                System.out.println("Doors content mismatch.");
            }
        }else{
            System.out.println("Basic room compare failed.");
        }
        
        if(needsUpdate){         
            System.out.println("Update required for "+room.getRoomId());
            return updateRoom(registeredRoom.getString("_id"));
        }else{
            System.out.println("Room "+room.getRoomId()+" is still up to date in Map, no need to update.");
            RegistrationResult r = new RegistrationResult();
            r.type = RegistrationResult.Type.REGISTERED;
            r.registeredObject = registeredRoom;
            return r;
        }      
    }
    
    private RegistrationResult registerRoom() throws Exception{
        return registerOrUpdateRoom(Mode.REGISTER, null);
    }
    
    private RegistrationResult updateRoom(String id) throws Exception{
        return registerOrUpdateRoom(Mode.UPDATE, id);
    }
    
    enum Mode {REGISTER,UPDATE};
    private RegistrationResult registerOrUpdateRoom(Mode mode, String id) throws Exception{
        Client postClient = ClientBuilder.newClient();

        // add our shared secret so all our queries come from the
        // game-on.org id
        postClient.register(new GameOnHeaderAuthInterceptor(Constants.GAMEON_ID, secret));
        
        // create the jax-rs 2.0 client
        WebTarget root = postClient.target(mapLocation);  
        
        // build the registration/update payload (post data)
        JsonObjectBuilder registrationPayload = Json.createObjectBuilder();
        // add the basic room info.
        registrationPayload.add("name", room.getRoomId());
        registrationPayload.add("fullName", room.getRoomName());
        registrationPayload.add("description", room.getRoomDescription());
        // add the doorway descriptions we'd like the game to use if it
        // wires us to other rooms.
        JsonObjectBuilder doors = Json.createObjectBuilder();
        for(DoorDesc door : room.getDoors()){
            switch(door.direction){
                case NORTH:{
                    doors.add("n",door.description);
                    break;
                }
                case SOUTH:{
                    doors.add("s",door.description);
                    break;
                }
                case EAST:{
                    doors.add("e",door.description);
                    break;
                }
                case WEST:{
                    doors.add("w",door.description);
                    break;
                }
                case UP:{
                    doors.add("u",door.description);
                    break;
                }
                case DOWN:{
                    doors.add("d",door.description);
                    break;
                }
                default:{
                    throw new RuntimeException("Bad enum value "+door.direction);
                }
            }
        }
        registrationPayload.add("doors", doors.build());
        
        // add the connection info for the room to connect back to us..
        JsonObjectBuilder connInfo = Json.createObjectBuilder();
        connInfo.add("type", "websocket"); // the only current supported
                                           // type.
        connInfo.add("target", getEndpointForRoom());
        registrationPayload.add("connectionDetails", connInfo.build());

        Response response=null;
        switch(mode){
            case REGISTER:{
                Invocation.Builder builder = root.request(MediaType.APPLICATION_JSON);
                response = builder.post(Entity.json(registrationPayload.build().toString()));
                break;
            }
            case UPDATE:{
                Invocation.Builder builder = root.path("{roomId}").resolveTemplate("roomId", id).request(MediaType.APPLICATION_JSON);
                response = builder.put(Entity.json(registrationPayload.build().toString()));
                break;
            }
        }
        
        RegistrationResult r = new RegistrationResult();
        try {
            
            if ( (mode.equals(Mode.REGISTER) && Status.CREATED.getStatusCode() == response.getStatus()) ||
                 (mode.equals(Mode.UPDATE) && Status.OK.getStatusCode() == response.getStatus()) ){
                String regString = response.readEntity(String.class);
                JsonReader reader = Json.createReader(new StringReader(regString));
                JsonObject registrationResponse = reader.readObject();

                r.type = RegistrationResult.Type.REGISTERED;
                r.registeredObject = registrationResponse;
                
                System.out.println("Sucessful registration/update operation against ("+id+")("+Constants.GAMEON_ID+")("+room.getRoomId()+") : "+regString);
            } else {
                String resp = response.readEntity(String.class);
                System.out.println("Error registering room provider : " + room.getRoomName() + " : status code "
                        + response.getStatus()+"\n"+ resp);

                r.type = RegistrationResult.Type.NOT_REGISTERED;
                
                throw new Exception("Room operation did not report success, got error code "+response.getStatus()+" "+response.getStatusInfo().getReasonPhrase());
            }
        } finally {
            response.close();
        }
        return r;
    }

    private String getEndpointForRoom() {
        return endPoint + "/ws/" +room.getRoomId();
    }
   

}
