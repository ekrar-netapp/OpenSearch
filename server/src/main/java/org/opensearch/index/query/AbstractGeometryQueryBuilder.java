/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.query;

import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.SetOnce;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.client.Client;
import org.opensearch.common.ParseField;
import org.opensearch.common.ParsingException;
import org.opensearch.common.geo.GeoJson;
import org.opensearch.common.geo.GeometryIO;
import org.opensearch.common.geo.GeometryParser;
import org.opensearch.common.geo.ShapeRelation;
import org.opensearch.common.geo.builders.ShapeBuilder;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.geometry.Geometry;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.mapper.MapperService;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Base {@link QueryBuilder} that builds a Geometry Query
 *
 * @opensearch.internal
 */
public abstract class AbstractGeometryQueryBuilder<QB extends AbstractGeometryQueryBuilder<QB>> extends AbstractQueryBuilder<QB> {

    public static final String DEFAULT_SHAPE_INDEX_NAME = "shapes";
    public static final String DEFAULT_SHAPE_FIELD_NAME = "shape";
    public static final ShapeRelation DEFAULT_SHAPE_RELATION = ShapeRelation.INTERSECTS;

    /** The default value for ignore_unmapped. */
    public static final boolean DEFAULT_IGNORE_UNMAPPED = false;

    protected static final ParseField SHAPE_FIELD = new ParseField("shape");
    protected static final ParseField RELATION_FIELD = new ParseField("relation");
    protected static final ParseField INDEXED_SHAPE_FIELD = new ParseField("indexed_shape");
    protected static final ParseField SHAPE_ID_FIELD = new ParseField("id");
    protected static final ParseField SHAPE_INDEX_FIELD = new ParseField("index");
    protected static final ParseField SHAPE_PATH_FIELD = new ParseField("path");
    protected static final ParseField SHAPE_ROUTING_FIELD = new ParseField("routing");
    protected static final ParseField IGNORE_UNMAPPED_FIELD = new ParseField("ignore_unmapped");

    protected final String fieldName;
    protected final Supplier<Geometry> supplier;

    protected final String indexedShapeId;

    protected Geometry shape;
    protected String indexedShapeIndex = DEFAULT_SHAPE_INDEX_NAME;
    protected String indexedShapePath = DEFAULT_SHAPE_FIELD_NAME;
    protected String indexedShapeRouting;

    protected ShapeRelation relation = DEFAULT_SHAPE_RELATION;

    protected boolean ignoreUnmapped = DEFAULT_IGNORE_UNMAPPED;

    /**
     * Creates a new ShapeQueryBuilder whose Query will be against the given
     * field name using the given Shape
     *
     * @param fieldName
     *            Name of the field that will be queried
     * @param shape
     *            Shape used in the Query
     * @deprecated use {@link #AbstractGeometryQueryBuilder(String, Geometry)} instead
     */
    @Deprecated
    protected AbstractGeometryQueryBuilder(String fieldName, ShapeBuilder shape) {
        this(fieldName, shape == null ? null : shape.buildGeometry(), null);
    }

    /**
     * Creates a new AbstractGeometryQueryBuilder whose Query will be against the given
     * field name using the given Shape
     *
     * @param fieldName
     *            Name of the field that will be queried
     * @param shape
     *            Shape used in the Query
     */
    public AbstractGeometryQueryBuilder(String fieldName, Geometry shape) {
        this(fieldName, shape, null);
    }

    /**
     * Creates a new ShapeQueryBuilder whose Query will be against the given
     * field name and will use the Shape found with the given ID
     *
     * @param fieldName
     *            Name of the field that will be filtered
     * @param indexedShapeId
     *            ID of the indexed Shape that will be used in the Query
     */
    protected AbstractGeometryQueryBuilder(String fieldName, String indexedShapeId) {
        this(fieldName, (Geometry) null, indexedShapeId);
    }

    protected AbstractGeometryQueryBuilder(String fieldName, Geometry shape, String indexedShapeId) {
        if (fieldName == null) {
            throw new IllegalArgumentException("fieldName is required");
        }
        if (shape == null && indexedShapeId == null) {
            throw new IllegalArgumentException("either shape or indexedShapeId is required");
        }
        this.fieldName = fieldName;
        this.shape = shape;
        this.indexedShapeId = indexedShapeId;
        this.supplier = null;
    }

    protected AbstractGeometryQueryBuilder(String fieldName, Supplier<Geometry> supplier, String indexedShapeId) {
        if (fieldName == null) {
            throw new IllegalArgumentException("fieldName is required");
        }
        if (supplier == null && indexedShapeId == null) {
            throw new IllegalArgumentException("either shape or indexedShapeId is required");
        }

        this.fieldName = fieldName;
        this.shape = null;
        this.supplier = supplier;
        this.indexedShapeId = indexedShapeId;
    }

    /**
     * Read from a stream.
     */
    protected AbstractGeometryQueryBuilder(StreamInput in) throws IOException {
        super(in);
        fieldName = in.readString();
        if (in.readBoolean()) {
            shape = GeometryIO.readGeometry(in);
            indexedShapeId = null;
        } else {
            shape = null;
            indexedShapeId = in.readOptionalString();
            if (in.getVersion().before(Version.V_2_0_0)) {
                String type = in.readOptionalString();
                assert MapperService.SINGLE_MAPPING_NAME.equals(type) : "Expected type [_doc], got [" + type + "]";
            }
            indexedShapeIndex = in.readOptionalString();
            indexedShapePath = in.readOptionalString();
            indexedShapeRouting = in.readOptionalString();
        }
        relation = ShapeRelation.readFromStream(in);
        ignoreUnmapped = in.readBoolean();
        supplier = null;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        if (supplier != null) {
            throw new IllegalStateException("supplier must be null, can't serialize suppliers, missing a rewriteAndFetch?");
        }
        out.writeString(fieldName);
        boolean hasShape = shape != null;
        out.writeBoolean(hasShape);
        if (hasShape) {
            GeometryIO.writeGeometry(out, shape);
        } else {
            out.writeOptionalString(indexedShapeId);
            if (out.getVersion().before(Version.V_2_0_0)) {
                out.writeOptionalString(MapperService.SINGLE_MAPPING_NAME);
            }
            out.writeOptionalString(indexedShapeIndex);
            out.writeOptionalString(indexedShapePath);
            out.writeOptionalString(indexedShapeRouting);
        }
        relation.writeTo(out);
        out.writeBoolean(ignoreUnmapped);
    }

    /**
     * @return the name of the field that will be queried
     */
    public String fieldName() {
        return fieldName;
    }

    /**
     * Sets the shapeBuilder for the query shape.
     *
     * @param geometry the geometry
     * @return this
     */
    public QB shape(Geometry geometry) {
        if (geometry == null) {
            throw new IllegalArgumentException("No geometry defined");
        }
        this.shape = geometry;
        return (QB) this;
    }

    /**
     * @return the shape used in the Query
     */
    public Geometry shape() {
        return shape;
    }

    /**
     * @return the ID of the indexed Shape that will be used in the Query
     */
    public String indexedShapeId() {
        return indexedShapeId;
    }

    /**
     * Sets the name of the index where the indexed Shape can be found
     *
     * @param indexedShapeIndex Name of the index where the indexed Shape is
     * @return this
     */
    public QB indexedShapeIndex(String indexedShapeIndex) {
        this.indexedShapeIndex = indexedShapeIndex;
        return (QB) this;
    }

    /**
     * @return the index name for the indexed Shape that will be used in the
     *         Query
     */
    public String indexedShapeIndex() {
        return indexedShapeIndex;
    }

    /**
     * Sets the path of the field in the indexed Shape document that has the Shape itself
     *
     * @param indexedShapePath Path of the field where the Shape itself is defined
     * @return this
     */
    public QB indexedShapePath(String indexedShapePath) {
        this.indexedShapePath = indexedShapePath;
        return (QB) this;
    }

    /**
     * @return the path of the indexed Shape that will be used in the Query
     */
    public String indexedShapePath() {
        return indexedShapePath;
    }

    /**
     * Sets the optional routing to the indexed Shape that will be used in the query
     *
     * @param indexedShapeRouting indexed shape routing
     * @return this
     */
    public QB indexedShapeRouting(String indexedShapeRouting) {
        this.indexedShapeRouting = indexedShapeRouting;
        return (QB) this;
    }

    /**
     * @return the optional routing to the indexed Shape that will be used in the
     *         Query
     */
    public String indexedShapeRouting() {
        return indexedShapeRouting;
    }

    /**
     * Sets the relation of query shape and indexed shape.
     *
     * @param relation relation of the shapes
     * @return this
     */
    public QB relation(ShapeRelation relation) {
        if (relation == null) {
            throw new IllegalArgumentException("No Shape Relation defined");
        }
        this.relation = relation;
        return (QB) this;
    }

    /**
     * @return the relation of query shape and indexed shape to use in the Query
     */
    public ShapeRelation relation() {
        return relation;
    }

    /**
     * Sets whether the query builder should ignore unmapped fields (and run a
     * {@link MatchNoDocsQuery} in place of this query) or throw an exception if
     * the field is unmapped.
     */
    public AbstractGeometryQueryBuilder<QB> ignoreUnmapped(boolean ignoreUnmapped) {
        this.ignoreUnmapped = ignoreUnmapped;
        return this;
    }

    /**
     * Gets whether the query builder will ignore unmapped fields (and run a
     * {@link MatchNoDocsQuery} in place of this query) or throw an exception if
     * the field is unmapped.
     */
    public boolean ignoreUnmapped() {
        return ignoreUnmapped;
    }

    /** builds the appropriate lucene shape query */
    protected abstract Query buildShapeQuery(QueryShardContext context, MappedFieldType fieldType);

    /** writes the xcontent specific to this shape query */
    protected abstract void doShapeQueryXContent(XContentBuilder builder, Params params) throws IOException;

    /** creates a new ShapeQueryBuilder from the provided field name and shape builder */
    protected abstract AbstractGeometryQueryBuilder<QB> newShapeQueryBuilder(String fieldName, Geometry shape);

    /** creates a new ShapeQueryBuilder from the provided field name, supplier, indexed shape id */
    protected abstract AbstractGeometryQueryBuilder<QB> newShapeQueryBuilder(
        String fieldName,
        Supplier<Geometry> shapeSupplier,
        String indexedShapeId
    );

    @Override
    protected Query doToQuery(QueryShardContext context) {
        if (shape == null || supplier != null) {
            throw new UnsupportedOperationException("query must be rewritten first");
        }
        final MappedFieldType fieldType = context.fieldMapper(fieldName);
        if (fieldType == null) {
            if (ignoreUnmapped) {
                return new MatchNoDocsQuery();
            } else {
                throw new QueryShardException(context, "failed to find type for field [" + fieldName + "]");
            }
        }
        return buildShapeQuery(context, fieldType);
    }

    /**
     * Fetches the Shape with the given ID in the given type and index.
     *
     * @param getRequest
     *            GetRequest containing index, type and id
     * @param path
     *            Name or path of the field in the Shape Document where the
     *            Shape itself is located
     */
    private void fetch(Client client, GetRequest getRequest, String path, ActionListener<Geometry> listener) {
        getRequest.preference("_local");
        client.get(getRequest, new ActionListener<GetResponse>() {

            @Override
            public void onResponse(GetResponse response) {
                try {
                    if (!response.isExists()) {
                        throw new IllegalArgumentException("Shape with ID [" + getRequest.id() + "] not found");
                    }
                    if (response.isSourceEmpty()) {
                        throw new IllegalArgumentException("Shape with ID [" + getRequest.id() + "] source disabled");
                    }

                    String[] pathElements = path.split("\\.");
                    int currentPathSlot = 0;

                    // It is safe to use EMPTY here because this never uses namedObject
                    try (
                        XContentParser parser = XContentHelper.createParser(
                            NamedXContentRegistry.EMPTY,
                            LoggingDeprecationHandler.INSTANCE,
                            response.getSourceAsBytesRef()
                        )
                    ) {
                        XContentParser.Token currentToken;
                        while ((currentToken = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (currentToken == XContentParser.Token.FIELD_NAME) {
                                if (pathElements[currentPathSlot].equals(parser.currentName())) {
                                    parser.nextToken();
                                    if (++currentPathSlot == pathElements.length) {
                                        listener.onResponse(new GeometryParser(true, true, true).parse(parser));
                                        return;
                                    }
                                } else {
                                    parser.nextToken();
                                    parser.skipChildren();
                                }
                            }
                        }
                        throw new IllegalStateException("Shape with name [" + getRequest.id() + "] found but missing " + path + " field");
                    }
                } catch (Exception e) {
                    onFailure(e);
                }
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(getWriteableName());

        builder.startObject(fieldName);

        if (shape != null) {
            builder.field(SHAPE_FIELD.getPreferredName());
            GeoJson.toXContent(shape, builder, params);
        } else {
            builder.startObject(INDEXED_SHAPE_FIELD.getPreferredName()).field(SHAPE_ID_FIELD.getPreferredName(), indexedShapeId);
            if (indexedShapeIndex != null) {
                builder.field(SHAPE_INDEX_FIELD.getPreferredName(), indexedShapeIndex);
            }
            if (indexedShapePath != null) {
                builder.field(SHAPE_PATH_FIELD.getPreferredName(), indexedShapePath);
            }
            if (indexedShapeRouting != null) {
                builder.field(SHAPE_ROUTING_FIELD.getPreferredName(), indexedShapeRouting);
            }
            builder.endObject();
        }

        if (relation != null) {
            builder.field(RELATION_FIELD.getPreferredName(), relation.getRelationName());
        }

        doShapeQueryXContent(builder, params);
        builder.endObject();
        builder.field(IGNORE_UNMAPPED_FIELD.getPreferredName(), ignoreUnmapped);

        printBoostAndQueryName(builder);

        builder.endObject();
    }

    @Override
    protected boolean doEquals(AbstractGeometryQueryBuilder other) {
        return Objects.equals(fieldName, other.fieldName)
            && Objects.equals(indexedShapeId, other.indexedShapeId)
            && Objects.equals(indexedShapeIndex, other.indexedShapeIndex)
            && Objects.equals(indexedShapePath, other.indexedShapePath)
            && Objects.equals(indexedShapeRouting, other.indexedShapeRouting)
            && Objects.equals(relation, other.relation)
            && Objects.equals(shape, other.shape)
            && Objects.equals(supplier, other.supplier)
            && Objects.equals(ignoreUnmapped, other.ignoreUnmapped);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(
            fieldName,
            indexedShapeId,
            indexedShapeIndex,
            indexedShapePath,
            indexedShapeRouting,
            relation,
            shape,
            ignoreUnmapped,
            supplier
        );
    }

    @Override
    protected QueryBuilder doRewrite(QueryRewriteContext queryRewriteContext) throws IOException {
        if (supplier != null) {
            return supplier.get() == null ? this : newShapeQueryBuilder(this.fieldName, supplier.get()).relation(relation);
        } else if (this.shape == null) {
            SetOnce<Geometry> supplier = new SetOnce<>();
            queryRewriteContext.registerAsyncAction((client, listener) -> {
                GetRequest getRequest = new GetRequest(indexedShapeIndex, indexedShapeId);
                getRequest.routing(indexedShapeRouting);
                fetch(client, getRequest, indexedShapePath, ActionListener.wrap(builder -> {
                    supplier.set(builder);
                    listener.onResponse(null);
                }, listener::onFailure));
            });
            return newShapeQueryBuilder(this.fieldName, supplier::get, this.indexedShapeId).relation(relation);
        }
        return this;
    }

    /**
     * local class that encapsulates xcontent parsed shape parameters
     *
     * @opensearch.internal
     */
    protected abstract static class ParsedGeometryQueryParams {
        public String fieldName;
        public ShapeRelation relation;
        public ShapeBuilder shape;

        public String id = null;
        public String index = null;
        public String shapePath = null;
        public String shapeRouting = null;

        public float boost;
        public String queryName;
        public boolean ignoreUnmapped;

        protected abstract boolean parseXContentField(XContentParser parser) throws IOException;
    }

    public static ParsedGeometryQueryParams parsedParamsFromXContent(XContentParser parser, ParsedGeometryQueryParams params)
        throws IOException {
        String fieldName = null;
        XContentParser.Token token;
        String currentFieldName = null;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                if (fieldName != null) {
                    throw new ParsingException(parser.getTokenLocation(), "point specified twice. [" + currentFieldName + "]");
                }
                fieldName = currentFieldName;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentFieldName = parser.currentName();
                        token = parser.nextToken();
                        if (RELATION_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                            params.relation = ShapeRelation.getRelationByName(parser.text());
                            if (params.relation == null) {
                                throw new ParsingException(parser.getTokenLocation(), "Unknown shape operation [" + parser.text() + " ]");
                            }
                        } else if (params.parseXContentField(parser)) {
                            continue;
                        } else if (INDEXED_SHAPE_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                                if (token == XContentParser.Token.FIELD_NAME) {
                                    currentFieldName = parser.currentName();
                                } else if (token.isValue()) {
                                    if (SHAPE_ID_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                        params.id = parser.text();
                                    } else if (SHAPE_INDEX_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                        params.index = parser.text();
                                    } else if (SHAPE_PATH_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                        params.shapePath = parser.text();
                                    } else if (SHAPE_ROUTING_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                                        params.shapeRouting = parser.text();
                                    }
                                } else {
                                    throw new ParsingException(
                                        parser.getTokenLocation(),
                                        "unknown token [" + token + "] after [" + currentFieldName + "]"
                                    );
                                }
                            }
                        } else {
                            throw new ParsingException(parser.getTokenLocation(), "query does not support [" + currentFieldName + "]");
                        }
                    }
                }
            } else if (token.isValue()) {
                if (AbstractQueryBuilder.BOOST_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    params.boost = parser.floatValue();
                } else if (AbstractQueryBuilder.NAME_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    params.queryName = parser.text();
                } else if (IGNORE_UNMAPPED_FIELD.match(currentFieldName, parser.getDeprecationHandler())) {
                    params.ignoreUnmapped = parser.booleanValue();
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "query does not support [" + currentFieldName + "]");
                }
            }
        }
        params.fieldName = fieldName;
        return params;
    }
}
