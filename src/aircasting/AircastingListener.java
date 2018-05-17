package aircasting;

        import java.io.IOException;
        import java.io.InputStream;
        import java.io.OutputStream;
        import java.text.ParseException;
        import java.util.ArrayList;
        import java.util.LinkedList;
        import java.util.List;
        import java.util.concurrent.TimeUnit;
        import java.util.concurrent.atomic.AtomicBoolean;

        import javax.bluetooth.DataElement;
        import javax.bluetooth.DeviceClass;
        import javax.bluetooth.DiscoveryAgent;
        import javax.bluetooth.DiscoveryListener;
        import javax.bluetooth.LocalDevice;
        import javax.bluetooth.RemoteDevice;
        import javax.bluetooth.ServiceRecord;
        import javax.bluetooth.UUID;
        import javax.microedition.io.Connector;
        import javax.microedition.io.StreamConnection;


public class AircastingListener implements DiscoveryListener {

    private static AtomicBoolean restartRequired = new AtomicBoolean(false);

    static class BluetoothReaderHelper implements Runnable {
        private StreamConnection streamConnection;
        BluetoothReaderHelper(StreamConnection streamConnection){
            this.streamConnection = streamConnection;
        }
        public void run() {
            boolean receivingData = false;
            System.err.println("Reader thread is running...");
            byte[] buf = new byte[4096];
            StringBuilder builder = new StringBuilder(1024);
            InputStream inputStream = null;
            try {
                inputStream = streamConnection.openInputStream();
                while (true) {
                    if(inputStream.available() > 20) {
                        if(!receivingData){
                            receivingData=true;
                        }
                        int dataRead = inputStream.read(buf);
                        String s = new String(buf, 0, dataRead,  "UTF-8").replace("\r","");
                        builder.append(s);
                        if(s.contains("\n")) {
                            String workingString = builder.toString();
                            int lfIndex = workingString.indexOf('\n');
                            while(lfIndex >= 0){
                                String line = workingString.substring(0, lfIndex);
                                if(line.length() > 0){
                                    try {
                                        SensorEvent se = parser.parse(line);
                                        System.out.println("<" + se.getSensorName() + ">" + se.getValue() + "</" + se.getSensorName() + ">");
                                    }
                                    catch(aircasting.ParseException ex){
                                        ex.printStackTrace(System.err);
                                    }
                                }
                                workingString = workingString.substring(lfIndex+1);
                                lfIndex = workingString.indexOf('\n');
                            }
                            if(workingString.length() > 0){
                                builder.setLength(0);
                                builder.append(workingString);
                            }
                        }
                    }
                    else
                    {
                        if(!receivingData){
                            System.err.print('.');
                            Thread.sleep(500);
                        }
                        else
                        {
                            Thread.sleep(50);
                        }
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace(System.err);
            } finally {
                try {
                    restartRequired.set(true);
                    if (inputStream != null) inputStream.close();
                }
                catch (Throwable tt){}
                System.err.println("Reader thread is stopped running...");
            }
        }
    }

    static class BluetoothWriterHelper implements Runnable {
        private StreamConnection streamConnection;
        BluetoothWriterHelper(StreamConnection streamConnection){
            this.streamConnection = streamConnection;
        }
        long last =0;

        public void writeCyclic(OutputStream outputStream) throws IOException
        {
            long current = System.currentTimeMillis();
            if(current - last > TimeUnit.SECONDS.toMillis(3))
            {
                outputStream.write(new byte[] { 2, 35, 0, 0, 3 });
                last = System.currentTimeMillis();
            }
        }

        public void run() {
            System.err.println("Writer thread is running...");
            try {
                //Create PUT Operation
                byte[] summaryData = PacketType.SummaryPacket.getRequest(Packet.Request.ENABLED);
                byte[] rToRData = PacketType.RtoRPacket.getRequest(Packet.Request.ENABLED);

                OutputStream outputStream = null;
                try {
                    outputStream = streamConnection.openOutputStream();
                    outputStream.write(summaryData);
                    outputStream.write(rToRData);
                    while(true){
                        writeCyclic(outputStream);
                        try{
                            Thread.sleep(100);
                        }
                        catch(Throwable tt){}

                    }
                }
                catch (Throwable t) {
                    System.err.println(t.toString());
                }
                finally {
                    try {
                        if (outputStream != null) outputStream.close();
                    }
                    catch(Throwable tt){}
                }
            }
            catch(Throwable t){
                t.printStackTrace(System.err);
            }
            finally {
                try {
                    restartRequired.set(true);
                    System.err.println("WriterReader thread has stopped running.");
                }
                catch(Throwable tt){}
            }
        }
    }

    private static Object lock = new Object();
    public ArrayList<RemoteDevice> devices;

    public AircastingListener() {
        devices = new ArrayList<RemoteDevice>();
    }

    public static void main(String[] args) {
        while(true) {

            AircastingListener listener = null;

            try {
                listener = new AircastingListener();
                LocalDevice localDevice = LocalDevice.getLocalDevice();
                DiscoveryAgent agent = localDevice.getDiscoveryAgent();
                agent.startInquiry(DiscoveryAgent.GIAC, listener);

                try {
                    synchronized (lock) {
                        lock.wait();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                    return;
                }


                System.err.println("Device Inquiry Completed. ");


                UUID[] uuidSet = new UUID[1];
                uuidSet[0] = new UUID(0x1101); //OBEX Object Push service

                int[] attrIDs = new int[]{
                        0x0100 // Service name
                };

                for (RemoteDevice device : listener.devices) {
                    if (device.getFriendlyName(false).toLowerCase().contains("airbeam")) {
                        agent.searchServices(attrIDs, uuidSet, device, listener);
                    }

                    try {
                        synchronized (lock) {
                            lock.wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace(System.err);
                        return;
                    }


                    System.err.println("Service search finished.");
                    while(!restartRequired.get()){
                        Thread.sleep(1000);
                    }
                }

            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    @Override
    public void deviceDiscovered(RemoteDevice btDevice, DeviceClass arg1) {
        String name;
        try {
            name = btDevice.getFriendlyName(false);
        } catch (Exception e) {
            name = btDevice.getBluetoothAddress();
        }

        devices.add(btDevice);
        System.err.println("device found: " + name);

    }

    @Override
    public void inquiryCompleted(int arg0) {
        synchronized (lock) {
            lock.notify();
        }
    }

    @Override
    public void serviceSearchCompleted(int arg0, int arg1) {
        synchronized (lock) {
            lock.notify();
        }
    }

    @Override
    public void servicesDiscovered(int transID, ServiceRecord[] servRecord) {
        boolean serviceFound = false;

        for (int i = 0; !serviceFound && i < servRecord.length; i++) {
            String url = servRecord[i].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
            if (url == null) {
                continue;
            }
            DataElement serviceName = servRecord[i].getAttributeValue(0x0100);
            if (serviceName != null) {
                System.err.println("service " + serviceName.getValue() + " found " + url);
                serviceFound = sendMessageToDevice(url);
            } else {
                System.err.println("service found " + url);
            }
        }
    }

    private static Thread readerThread;
    private static Thread writerThread;
    private static ExternalSensorParser parser = new ExternalSensorParser();

    private static boolean sendMessageToDevice(String serverURL) {
        try {
            System.err.println("Connecting to " + serverURL);

            Object client = Connector.open(serverURL);
            if(client instanceof StreamConnection)
            {
                System.err.println("client Connecting to Stream connection");
                StreamConnection streamConnection = (StreamConnection) client;
                readerThread = new Thread(new BluetoothReaderHelper(streamConnection));
                writerThread = new Thread(new BluetoothWriterHelper(streamConnection));
                writerThread.start();
                readerThread.start();
                return true;
            }
            else {
                System.err.println("Client of class " + client.getClass().getName() + " not implemented");
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return false;
    }
}