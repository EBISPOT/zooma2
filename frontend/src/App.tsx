
import * as React from 'react'
import Home from './pages/Home';
import DocsApi from './pages/docs/api';
import About from './pages/about';
import Header from './components/Header';

import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useParams,
  useSearchParams,
} from "react-router-dom";

export default function App() {
return (
  <BrowserRouter basename={process.env.PUBLIC_URL}>
    <Routes>
      <Route path='/' element={<Home/>}></Route>
      <Route path='/docs' element={<DocsApi/>}></Route>
      <Route path='/about' element={<About/>}></Route>
    </Routes>
    </BrowserRouter>
  );
}
