package com.utorrent.webapiwrapper.core;

import com.google.common.collect.ImmutableList;
import com.utorrent.webapiwrapper.core.entities.*;
import com.utorrent.webapiwrapper.restclient.ConnectionParams;
import com.utorrent.webapiwrapper.restclient.RESTClient;
import com.utorrent.webapiwrapper.restclient.Request;
import com.utorrent.webapiwrapper.restclient.exceptions.BadRequestException;
import org.apache.http.client.utils.URIBuilder;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static com.utorrent.webapiwrapper.core.Action.*;
import static com.utorrent.webapiwrapper.core.entities.RequestResult.FAIL;
import static com.utorrent.webapiwrapper.core.entities.RequestResult.SUCCESS;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

class UTorrentWebAPIClientImpl implements UTorrentWebAPIClient {

    private static final String ACTION_QUERY_PARAM_NAME = "action";
    private static final String TOKEN_PARAM_NAME = "token";
    private static final String URL_PARAM_NAME = "s";
    private static final String LIST_QUERY_PARAM_NAME = "list";
    private static final String CACHE_ID_QUERY_PARAM = "cid";
    private static final String HASH_QUERY_PARAM_NAME = "hash";
    private static final String FILE_INDEX_QUERY_PARAM_NAME = "f";
    private static final String PRIORITY_QUERY_PARAM_NAME = "p";
    private static final String TORRENT_FILE_PART_NAME = "torrent_file";

    private final RESTClient client;
    private final TorrentsCache torrentsCache;
    private final URI serverURI;

    private final MessageParser messageParser;

    private String token;


    public UTorrentWebAPIClientImpl(RESTClient client, ConnectionParams connectionParams, MessageParser messageParser) {
        this.client = client;
        URIBuilder uriBuilder = new URIBuilder()
                .setScheme(connectionParams.getScheme())
                .setHost(connectionParams.getHost())
                .setPort(connectionParams.getPort())
                .setPath("/gui/");
        this.serverURI = getURI(uriBuilder);
        this.messageParser = messageParser;
        torrentsCache = new TorrentsCache();
    }

    private String getAuthenticationToken() {
        if (token == null) {
            synchronized (this) {
                if (token == null) {
                    token = client.get(serverURI.resolve("token.html"));
                    requireNonNull(token, "Token received is null");
                    token = token.replaceAll("<[^>]*>", "");
                }
            }
        }

        return token;
    }

    private <T> T invokeWithAuthentication(URIBuilder uriBuilder, Function<URI, T> responseSupplier, boolean retryIfAuthFailed) {

        try {
            uriBuilder.addParameter(TOKEN_PARAM_NAME, getAuthenticationToken());
            T response = responseSupplier.apply(getURI(uriBuilder));
            requireNonNull(response, String.format("Received null response from server, request %s", responseSupplier));
            return response;
        } catch (BadRequestException e) {
            setTokenAsExpired();
            if (retryIfAuthFailed) {
                return invokeWithAuthentication(uriBuilder, responseSupplier, false);
            } else {
                throw new UTorrentAuthException("Impossible to connect to uTorrents, wrong username or password", e);
            }
        }
    }

    @Override
    public RequestResult addTorrent(String url) {
        List<Request.QueryParam> params = new ArrayList<>();
        params.add(new Request.QueryParam(URL_PARAM_NAME, url));
        String result = executeAction(ADD_URL, ImmutableList.of(), params);
        return getResult(result);
    }

    @Override
    public RequestResult addTorrent(File torrentFile) {

        URIBuilder uriBuilder = new URIBuilder(serverURI)
                .addParameter(ACTION_QUERY_PARAM_NAME, ADD_FILE.getName());
        Request.RequestBuilder request = Request.builder()
                .addFile(TORRENT_FILE_PART_NAME, torrentFile, APPLICATION_X_BIT_TORRENT_CONTENT_TYPE);

        String stringResult = invokeWithAuthentication(uriBuilder, uri -> client.post(request.setDestination(uri).create()), true);
        return getResult(stringResult);
    }

    @Override
    public Set<Torrent> getTorrentList() {
        updateTorrentCache();
        return torrentsCache.getTorrentList();
    }

    private void updateTorrentCache() {
        URIBuilder requestBuilder = new URIBuilder(serverURI)
                .addParameter(LIST_QUERY_PARAM_NAME, "1");

        if (nonNull(torrentsCache.getCachedID())) {
            requestBuilder.addParameter(CACHE_ID_QUERY_PARAM, torrentsCache.getCachedID());
        }

        String jsonTorrentSnapshotMessage = invokeWithAuthentication(requestBuilder, client::get, true);
        torrentsCache.updateCache(messageParser.parseAsTorrentListSnapshot(jsonTorrentSnapshotMessage));
    }

    @Override
    public Torrent getTorrent(String torrentHash) {
        updateTorrentCache();
        return torrentsCache.getTorrent(torrentHash);
    }

    @Override
    public TorrentFileList getTorrentFiles(List<String> torrentHashes) {
        String torrentFilesJsonMessage = executeAction(GET_FILES, torrentHashes, ImmutableList.of());
        return messageParser.parseAsTorrentFileList(torrentFilesJsonMessage);
    }

    @Override
    public TorrentProperties getTorrentProperties(List<String> torrentHash) {
        String jsonTorrentPropertiesMessage = executeAction(GET_PROP, ImmutableList.copyOf(torrentHash), ImmutableList.of());
        return messageParser.parseAsTorrentProperties(jsonTorrentPropertiesMessage);
    }

    @Override
    public RequestResult startTorrent(List<String> hash) {
        return executeBaseTorrentAction(START, hash);
    }

    @Override
    public RequestResult stopTorrent(List<String> hash) {
        return executeBaseTorrentAction(STOP, hash);
    }

    @Override
    public RequestResult pauseTorrent(List<String> hash) {
        return executeBaseTorrentAction(PAUSE, hash);
    }

    @Override
    public RequestResult forceStartTorrent(List<String> hash) {
        return executeBaseTorrentAction(FORCE_START, hash);
    }

    @Override
    public RequestResult unpauseTorrent(List<String> hash) {
        return executeBaseTorrentAction(UN_PAUSE, hash);
    }

    @Override
    public RequestResult recheckTorrent(List<String> hash) {
        return executeBaseTorrentAction(RECHECK, hash);
    }

    @Override
    public RequestResult removeTorrent(List<String> hash) {
        return executeBaseTorrentAction(REMOVE, hash);
    }

    @Override
    public RequestResult removeDataTorrent(List<String> hash) {
        return executeBaseTorrentAction(REMOVE_DATA, hash);
    }

    @Override
    public RequestResult setTorrentFilePriority(String hash, Priority priority,
                                                List<Integer> fileIndices) {
        List<Request.QueryParam> params = new ArrayList<>();
        params.add(new Request.QueryParam(PRIORITY_QUERY_PARAM_NAME, String.valueOf(priority.getValue())));
        fileIndices.forEach(index -> params.add(new Request.QueryParam(FILE_INDEX_QUERY_PARAM_NAME, String.valueOf(index))));
        return getResult(executeAction(SET_PRIORITY, ImmutableList.of(hash), params));
    }

    @Override
    public RequestResult setClientSetting(SettingsKey settingKey, String settingValue) {
        return setClientSetting(settingKey.getKeyValue(), settingValue);
    }

    @Override
    public RequestResult setClientSetting(String settingName, String settingValue) {
        return setClientSetting(ImmutableList.of(new Request.QueryParam(settingName, settingValue)));
    }

    @Override
    public RequestResult setClientSetting(List<Request.QueryParam> settings) {
        return getResult(executeAction(SET_SETTING, ImmutableList.of(), settings));
    }

    @Override
    public ClientSettings getClientSettings() {
        String returnedValue = executeAction(GET_SETTINGS);
        return messageParser.parseAsClientSettings(returnedValue);
    }

    private RequestResult executeBaseTorrentAction(Action action, List<String> hashes) {
        return getResult(executeAction(action, hashes, ImmutableList.of()));
    }

    private String executeAction(Action action) {
        return executeAction(action, ImmutableList.of(), ImmutableList.of());
    }

    private String executeAction(Action action, List<String> torrentHashes, List<Request.QueryParam> queryParams) {
        URIBuilder requestBuilder = new URIBuilder(serverURI)
                .addParameter(ACTION_QUERY_PARAM_NAME, action.getName());
        queryParams.forEach(param -> requestBuilder.addParameter(param.getName(), param.getValue()));
        torrentHashes.forEach(hash -> requestBuilder.addParameter(HASH_QUERY_PARAM_NAME, hash));
        return invokeWithAuthentication(requestBuilder, client::get, true);
    }

    private void setTokenAsExpired() {
        token = null;
    }

    @Override
    public void close() throws IOException {
        this.client.close();
    }

    private URI getURI(URIBuilder requestBuilder) {
        try {
            return requestBuilder.build();
        } catch (URISyntaxException e) {
            throw new RuntimeException("URISyntaxException occurred!", e);
        }
    }

    private RequestResult getResult(String result) {
        return nonNull(result) && result.contains("build") ? SUCCESS : FAIL;
    }
}
