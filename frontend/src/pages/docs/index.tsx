import { Link } from 'react-router-dom'
import React, { Fragment } from "react";

export default function docs() {
    return (
            <main>
                <div>
                    <Link to="/docs/search">
                        <div style={{cursor: 'pointer'}}>
                            <h2>Searching ZOOMA</h2>
                            <p>Check here for an overview on how to use Zooma, and how to search for ontology mappings given metadata descriptions</p>
                        </div>
                    </Link>
                </div>
                <div>
                    <Link to="/docs/api">
                        <div style={{cursor: 'pointer'}}>
                            <h2>REST API</h2>
                            <p>Zooma developer documentation, including how to code tools against the Zooma REST API.</p>
                        </div>
                    </Link>
                </div>
                <div>
                    <Link to="/docs/developers">
                        <div style={{cursor: 'pointer'}}>
                            <h2>Annotation model</h2>
                            <p>Information about the ZOOMA annotation model and data loading.</p>
                        </div>
                    </Link>
                </div>
            </main>
    )
}
