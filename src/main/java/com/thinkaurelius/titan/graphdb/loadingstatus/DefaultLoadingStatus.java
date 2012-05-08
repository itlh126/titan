package com.thinkaurelius.titan.graphdb.loadingstatus;

import com.thinkaurelius.titan.graphdb.edgequery.InternalEdgeQuery;

public class DefaultLoadingStatus implements LoadingStatus {
	
	private final boolean defaultStatus;
	
	DefaultLoadingStatus(boolean status) {
		defaultStatus = status;
	}

	@Override
	public boolean hasLoadedEdges(InternalEdgeQuery query) {
		return defaultStatus;
	}

	@Override
	public LoadingStatus loadedEdges(InternalEdgeQuery query) {
		if (!defaultStatus) {
			BasicLoadingStatus update = new BasicLoadingStatus();
			update.loadedEdges(query);
			return update;
		} else return this;
	}

	
}