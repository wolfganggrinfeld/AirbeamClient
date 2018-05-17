package aircasting;

public abstract class Packet
{
    public enum Request
    {
        ENABLED(true),
        DISABLED(false);

        private boolean enabled;

        Request(boolean enabled)
        {
            this.enabled = enabled;
        }

        public boolean isEnabled()
        {
            return enabled;
        }
    }
}
