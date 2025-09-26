
import React, { Fragment, Component } from "react"

export function EBIFooter() {
    return (
        <footer>
            {/*<div id="global-footer" className="global-footer">*/}
            {/*<nav id="global-nav-expanded" className="global-nav-expanded row">*/}
            {/*</nav>*/}
            {/*<section id="ebi-footer-meta" className="ebi-footer-meta row">*/}
            {/*</section>*/}
            {/*</div>*/}
            <section id="Zooma-footer">
                <p className="address">EMBL-EBI, Wellcome Trust Genome Campus, Hinxton, Cambridgeshire, CB10 1SD,
                    UK &nbsp; &nbsp; +44 (0)1223 49 44 44</p>
                <p className="legal">Copyright &copy; EMBL-EBI 2016 | <a
                    href={process.env.PUBLIC_URL + "/Privacy_notice_for_EMBL-EBI_Public_Website.pdf"}>Privacy</a></p>
            </section>
        </footer>
    )
}


