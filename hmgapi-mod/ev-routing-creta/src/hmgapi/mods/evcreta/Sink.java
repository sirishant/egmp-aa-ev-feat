package hmgapi.mods.evcreta;

/** Where the energy model goes: one whole Android Auto message. */
interface Sink {
    /** The library's result code. */
    int send(byte[] message);
}
